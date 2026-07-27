package com.taskqueue.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.taskqueue.TaskQueueApplication;
import com.taskqueue.model.Task;
import com.taskqueue.service.TaskNotFoundException;
import com.taskqueue.service.TaskQueryService;
import com.taskqueue.service.TaskSubmissionService;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests for {@link TaskController}.
 *
 * <p>Uses {@code @WebMvcTest} to load only the web slice — no database, no Spring Data.
 * Services are Mockito mocks. This tests the controller's HTTP contract:
 * status codes, response bodies, Location headers, and error handling.
 *
 * <p>From ch05: "Slice tests for the web layer. No database. No service logic.
 * Only routing, serialisation, validation, and error response shape."
 *
 * <p>Tests:
 * <ul>
 *   <li>POST /tasks → 202 Accepted + Location header
 *   <li>POST /tasks with duplicate idempotency key → 202 with existing task
 *   <li>POST /tasks with blank type → 400 Bad Request + field error
 *   <li>GET /tasks/{id} → 200 OK
 *   <li>GET /tasks/{id} for unknown id → 404 Not Found
 * </ul>
 */
@WebMvcTest(controllers = {TaskController.class, ApiExceptionHandler.class})
@ContextConfiguration(classes = TaskQueueApplication.class)
class TaskControllerIT {
    // Uses TaskController from com.taskqueue.web package

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskSubmissionService submissionService;

    @MockBean
    private TaskQueryService queryService;

    @MockBean
    private com.taskqueue.ratelimit.RateLimiter rateLimiter;

    private Task sampleTask;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire()).thenReturn(true);
        sampleTask = Task.create("test-id-123", "email", "{\"to\":\"x@x.com\"}", 3, 0);
    }

    // ── POST /tasks ─────────────────────────────────────────────────────────

    @Test
    void postTaskReturns202WithLocationHeader() throws Exception {
        when(submissionService.submit(any())).thenReturn(sampleTask);

        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "email", "payload": {"to": "test@example.com"}}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/tasks/test-id-123"))
                .andExpect(jsonPath("$.id").value("test-id-123"))
                .andExpect(jsonPath("$.type").value("email"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postTaskWithBlankTypeReturns400() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "", "payload": {"to": "x@x.com"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields[0].field").value("type"));
    }

    @Test
    void postTaskWithNullPayloadReturns400() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void postTaskWithInvalidMaxAttemptsReturns400() throws Exception {
        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "email", "payload": {}, "maxAttempts": 100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void postTaskWithDuplicateIdempotencyKeyReturnsSameTask() throws Exception {
        // Same task returned for duplicate idempotency key (dedup happened in service)
        when(submissionService.submit(any())).thenReturn(sampleTask);

        mockMvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "email", "payload": {}, "idempotencyKey": "charge-42"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("test-id-123"));
    }

    // ── GET /tasks/{id} ─────────────────────────────────────────────────────

    @Test
    void getTaskReturns200WhenFound() throws Exception {
        when(queryService.findById("test-id-123")).thenReturn(Optional.of(sampleTask));

        mockMvc.perform(get("/tasks/test-id-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-id-123"))
                .andExpect(jsonPath("$.type").value("email"));
    }

    @Test
    void getTaskReturns404WhenNotFound() throws Exception {
        when(queryService.findById("no-such")).thenThrow(new TaskNotFoundException("no-such"));

        mockMvc.perform(get("/tasks/no-such"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("task_not_found"));
    }
}
