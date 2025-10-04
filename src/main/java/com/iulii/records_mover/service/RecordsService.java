package com.iulii.records_mover.service;

import com.iulii.records_mover.models.MovableRecord;
import com.iulii.records_mover.repository.RecordsRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecordsService {

    private static final double MIN_GAP = 0.0000000001;
    private static final int BATCH_SIZE = 500;
    private final RecordsRepository repository;

    public Page<MovableRecord> getRecords(int page, int size) {
        int offset = page * size;
        List<MovableRecord> content = repository.findWithPagination(offset, size);
        long total = repository.countAllRecords();

        return new PageImpl<>(
                content,
                PageRequest.of(page, size),
                total
        );
    }

    // Перемещение элемента между двумя другими или перед элементом выше когда сверху нет элемента и после элемента ниже, когда внизу нет границы
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 50), retryFor = ConcurrentModificationException.class)
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void moveItemBetween(UUID itemId, UUID afterId, UUID beforeId) {
        repository.findByIdWithLock(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found"));

        NeighborContext neighborContext = getNeighborContext(afterId, beforeId);

        Double newPosition = findAvailablePosition(neighborContext.afterPos(), neighborContext.beforePos());

        neighborContext = refreshNeighborContext(neighborContext);

        if (repository.existsByPosition(newPosition)) {
            throw new ConcurrentModificationException("Position was occupied after calculation");
        }

        int updated = repository.updatePositionSafely(
                itemId,
                newPosition,
                neighborContext.afterId(),
                neighborContext.afterPos(),
                neighborContext.beforeId(),
                neighborContext.beforePos());

        if (updated == 0) {
            throw new ConcurrentModificationException("Unable to update position due to concurrent changes");
        }
    }

    // Рекурсивный поиск доступной позиции
    private Double findAvailablePosition(Double afterPos, Double beforePos) {
        if (beforePos - afterPos < MIN_GAP) {
            return createGap(afterPos, beforePos);
        }

        Double middle = (afterPos + beforePos) / 2;

        if (!repository.existsByPosition(middle)) {
            return middle;
        }

        // Рекурсивно находим новую позицию до середины и после середины
        Double result = findAvailablePosition(afterPos, middle);
        if (result == null) {
            result = findAvailablePosition(middle, beforePos);
        }

        return result;
    }

    // Похожу операцию можно осуществлять по расписанию для перебалансировки записей по позициям
    private Double createGap(Double afterPos, Double beforePos) {

        double requiredGap = MIN_GAP * 10; // Буфер в 3 раза больше минимума
        double existingGap = beforePos - afterPos;
        double shiftAmount = requiredGap - existingGap;

        if (shiftAmount <= 0) {
            return afterPos + (beforePos - afterPos) / 2;
        }

        // Пакетный сдвиг с блокировками
        shift500ItemsInBatches(beforePos, shiftAmount);

        double widenedBeforePos = beforePos + shiftAmount;

        return afterPos + (widenedBeforePos - afterPos) / 2;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private void shift500ItemsInBatches(Double fromPosition, Double shiftAmount) {
        List<MovableRecord> batch = repository.findAndLockNextItemsById(fromPosition, null, PageRequest.of(0, BATCH_SIZE));
        batch.forEach(item -> item.setPosition(item.getPosition() + shiftAmount));
        repository.saveAll(batch);
    }

    private NeighborContext getNeighborContext(UUID afterId, UUID beforeId) {
        Double afterPos = null;
        if (afterId != null) {
            afterPos = repository.findByIdWithLock(afterId)
                    .orElseThrow(() -> new EntityNotFoundException("After item not found"))
                    .getPosition();
        }

        Double beforePos;
        if (beforeId != null) {
            beforePos = repository.findByIdWithLock(beforeId)
                    .orElseThrow(() -> new EntityNotFoundException("Before item not found"))
                    .getPosition();
        } else {
            Double maxPosition = repository.findMaxPosition();
            beforePos = (maxPosition != null ? maxPosition : 0.0) + 100;
        }

        double afterPositionValue = afterPos != null ? afterPos : 0.0;

        return new NeighborContext(afterId, afterPositionValue, beforeId, beforePos);
    }

    private NeighborContext refreshNeighborContext(NeighborContext context) {
        Double refreshedAfterPos = context.afterPos();
        if (context.afterId() != null) {
            refreshedAfterPos = repository.findByIdWithLock(context.afterId())
                    .orElseThrow(() -> new EntityNotFoundException("After item not found"))
                    .getPosition();
        }

        Double refreshedBeforePos = context.beforePos();
        if (context.beforeId() != null) {
            refreshedBeforePos = repository.findByIdWithLock(context.beforeId())
                    .orElseThrow(() -> new EntityNotFoundException("Before item not found"))
                    .getPosition();
        }

        return new NeighborContext(context.afterId(), refreshedAfterPos, context.beforeId(), refreshedBeforePos);
    }


    private record NeighborContext(UUID afterId, Double afterPos, UUID beforeId, Double beforePos) {
    }
}
