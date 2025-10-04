package com.iulii.records_mover.controller;

import com.iulii.records_mover.models.MovableRecord;
import com.iulii.records_mover.service.RecordsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class RecordsController {
    private final RecordsService service;

    @PostMapping("/{id}/move")
    public ResponseEntity<Void> moveItem(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) UUID before) {

        if (after == null && before == null) {
            throw new IllegalArgumentException("Either 'after' or 'before' parameter must be provided");
        }

        if ((after != null && after.equals(id)) || (before != null && before.equals(id))) {
            throw new IllegalArgumentException("Item cannot be moved relative to itself");
        }

        if (after != null && before != null && after.equals(before)) {
            throw new IllegalArgumentException("'after' and 'before' cannot reference the same item");
        }

        service.moveItemBetween(id, after, before);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public Page<MovableRecord> getRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative");
        }

        if (size <= 0 || size > 500) {
            throw new IllegalArgumentException("Size must be between 1 and 500");
        }

        return service.getRecords(page, size);
    }
}