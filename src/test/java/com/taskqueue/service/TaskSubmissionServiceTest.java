package com.taskqueue.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.taskqueue.events.TaskSubmittedEvent;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.web.SubmitTaskRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TaskSubmissionService}.
 *
 * <p>No Spring context — all dependencies are Mockito mocks.
 * Clock is fixed to make assertions deterministic.
 *
 * <p>Tests:
 * <ul>
 *   <li>Task is created with UUID, type, PENDING status
 *   <li>Task is saved to the repository
 *   <li>TaskSubmittedEvent is published
 *   <li>Idempotency: existing task is returned; no new save
 *   <li>ScheduledAt in the future → task starts as SCHEDULED
 * </ul>
 */
class TaskSubmissionServiceTest {

    private TaskRepository repository;
    private ApplicationEventPublisher events;
    private TaskSubmissionService service;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TaskRepository.class);
        events     = Mockito.mock(ApplicationEventPublisher.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        service = new TaskSubmissionService(repository, fixedClock, events);
    }

    @Test
    void submitCreatesTaskWithPendingStatus() {
        SubmitTaskRequest request = request("email", null, null);

        Task task = service.submit(request);

        assertThat(task.type()).isEqualTo("email");
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.id()).isNotBlank();
        assertThat(task.attempts()).isZero();
    }

    @Test
    void submitSavesTaskToRepository() {
        SubmitTaskRequest request = request("email", null, null);
        service.submit(request);
        verify(repository).save(any(Task.class));
    }

    @Test
    void submitPublishesTaskSubmittedEvent() {
        SubmitTaskRequest request = request("email", null, null);
        service.submit(request);

        ArgumentCaptor<TaskSubmittedEvent> captor = ArgumentCaptor.forClass(TaskSubmittedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("email");
    }

    @Test
    void idempotencyKeyReturnsExistingTask() {
        Task existing = Task.create("existing-id", "email", "{}", 3, 0);
        when(repository.findByIdempotencyKey("charge-42")).thenReturn(Optional.of(existing));

        SubmitTaskRequest request = request("email", "charge-42", null);
        Task result = service.submit(request);

        assertThat(result.id()).isEqualTo("existing-id");
        // Should NOT save a new task — existing was returned
        verify(repository, never()).save(any());
        verify(repository, never()).saveWithIdempotencyKey(any(), any());
    }

    @Test
    void idempotencyKeyWithNoExistingSavesNewTask() {
        when(repository.findByIdempotencyKey("new-key")).thenReturn(Optional.empty());
        when(repository.saveWithIdempotencyKey(any(), eq("new-key"))).thenReturn(true);

        SubmitTaskRequest request = request("email", "new-key", null);
        service.submit(request);

        verify(repository).saveWithIdempotencyKey(any(Task.class), eq("new-key"));
    }

    @Test
    void futureScheduledAtCreatesScheduledTask() {
        Instant future = Instant.parse("2030-01-01T00:00:00Z");
        SubmitTaskRequest request = requestWithScheduledAt("email", future);

        Task task = service.submit(request);

        assertThat(task.status()).isEqualTo(TaskStatus.SCHEDULED);
        assertThat(task.scheduledAt()).isEqualTo(future);
    }

    @Test
    void defaultMaxAttemptsAndPriorityApplied() {
        SubmitTaskRequest request = request("report", null, null);
        Task task = service.submit(request);

        assertThat(task.maxAttempts()).isEqualTo(3);   // DEFAULT_MAX_ATTEMPTS
        assertThat(task.priority()).isZero();            // DEFAULT_PRIORITY
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private SubmitTaskRequest request(String type, String idempotencyKey, Instant scheduledAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("test", true);
        return new SubmitTaskRequest(type, payload, null, null, scheduledAt, idempotencyKey);
    }

    private SubmitTaskRequest requestWithScheduledAt(String type, Instant scheduledAt) {
        return request(type, null, scheduledAt);
    }
}
