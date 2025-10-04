package com.iulii.records_mover.service;

import com.iulii.records_mover.models.MovableRecord;
import com.iulii.records_mover.repository.RecordsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RecordsServiceIntegrationTest {

    @Autowired
    private RecordsService recordsService;

    @Autowired
    private RecordsRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void moveItemBetween_shouldShiftBatchWhenGapIsTooSmall() {
        MovableRecord after = saveRecord(1.0);
        MovableRecord before = saveRecord(1.00000000005);
        MovableRecord extra = saveRecord(1.0000000001);
        MovableRecord toMove = saveRecord(5.0);

        recordsService.moveItemBetween(toMove.getId(), after.getId(), before.getId());

        MovableRecord refreshedBefore = repository.findById(before.getId()).orElseThrow();
        MovableRecord refreshedExtra = repository.findById(extra.getId()).orElseThrow();
        MovableRecord refreshedMoved = repository.findById(toMove.getId()).orElseThrow();

        assertThat(refreshedBefore.getPosition()).isGreaterThan(1.00000000005);
        assertThat(refreshedExtra.getPosition()).isGreaterThan(1.0000000001);
        assertThat(refreshedMoved.getPosition())
                .isGreaterThan(after.getPosition())
                .isLessThan(refreshedBefore.getPosition());
    }

    @Test
    void concurrentMoves_shouldAssignUniquePositions() throws ExecutionException, InterruptedException {
        MovableRecord anchor = saveRecord(1.0);
        MovableRecord boundary = saveRecord(100.0);

        List<MovableRecord> movableRecords = IntStream.range(0, 5)
                .mapToObj(i -> saveRecord(200.0 + i))
                .toList();

        List<CompletableFuture<Void>> futures = movableRecords.stream()
                .map(record -> CompletableFuture.runAsync(() ->
                        recordsService.moveItemBetween(record.getId(), anchor.getId(), boundary.getId())))
                .toList();

        for (CompletableFuture<Void> future : futures) {
            future.get();
        }

        List<Double> positions = movableRecords.stream()
                .map(record -> repository.findById(record.getId()).orElseThrow().getPosition())
                .toList();

        long movedIntoRange = repository.findAll().stream()
                .filter(record -> record.getPosition() > anchor.getPosition() && record.getPosition() < boundary.getPosition())
                .count();

        assertThat(positions).allMatch(pos -> pos > anchor.getPosition() && pos < boundary.getPosition());
        assertThat(movedIntoRange).isGreaterThanOrEqualTo(movableRecords.size());
    }

    private MovableRecord saveRecord(double position) {
        MovableRecord record = new MovableRecord();
        record.setValue("value-" + position);
        record.setPosition(position);
        MovableRecord saved = repository.save(record);
        repository.flush();
        return saved;
    }
}
