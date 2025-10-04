package com.iulii.records_mover.controller;

import com.iulii.records_mover.models.MovableRecord;
import com.iulii.records_mover.service.RecordsService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RecordsController.class)
class RecordsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecordsService recordsService;

    @Test
    void moveItem_shouldValidateRequiredBoundaries() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/items/" + id + "/move"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Either 'after' or 'before' parameter must be provided")));
    }

    @Test
    void moveItem_shouldPropagateNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        UUID after = UUID.randomUUID();

        Mockito.doThrow(new EntityNotFoundException("Item not found"))
                .when(recordsService).moveItemBetween(id, after, null);

        mockMvc.perform(post("/api/items/" + id + "/move").param("after", after.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Item not found")));
    }

    @Test
    void moveItem_shouldTranslateConcurrentModification() throws Exception {
        UUID id = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        UUID before = UUID.randomUUID();

        Mockito.doThrow(new ConcurrentModificationException("conflict"))
                .when(recordsService).moveItemBetween(id, after, before);

        mockMvc.perform(post("/api/items/" + id + "/move")
                        .param("after", after.toString())
                        .param("before", before.toString()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("conflict")));
    }

    @Test
    void getRecords_shouldValidatePageRequest() throws Exception {
        mockMvc.perform(get("/api/items")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Page index must not be negative")));

        mockMvc.perform(get("/api/items")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Size must be between 1 and 500")));
    }

    @Test
    void getRecords_shouldReturnPage() throws Exception {
        Page<MovableRecord> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 10), 0);
        Mockito.when(recordsService.getRecords(0, 10)).thenReturn(page);

        mockMvc.perform(get("/api/items").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}
