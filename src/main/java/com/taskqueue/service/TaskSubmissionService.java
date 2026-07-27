package com.taskqueue.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskqueue.events.TaskSubmittedEvent;
import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.web.SubmitTaskRequest;

/**
 * Application service: submits tasks into the queue.
 *
 * <p>This is the bridge between the HTTP adapter ({@code TaskController}) and the
 * domain ports ({@code TaskRepository}, {@code TaskQueue}).
 *
 * <p>From ch02: "The controller owns transport concerns. The service owns
 * use-case behavior. The repository owns persistence."
 *
 * <p>From layered-architecture.md: the service is the Application Layer —
 * it orchestrates domain objects and calls outbound ports, but contains
 * no HTTP logic or SQL.
 *
 * <h2>Idempotency</h2>
 * <p>From idempotency.md: if the client provides an {@code idempotencyKey},
 * the service checks whether a task with that key already exists:
 * <ul>
 *   <li>If found → returns the existing task (same 202 response)
 *   <li>If not found → creates a new task and persists it
 * </ul>
 * The DB unique index on {@code idempotency_key} is the safety net that prevents
 * duplicates even under concurrent requests with the same key.
 */
@Service
public class TaskSubmissionService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_PRIORITY     = 0;

    private final TaskRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public TaskSubmissionService(
            TaskRepository repository,
            Clock clock,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock      = clock;
        this.events     = events;
    }

    /**
     * Submits a task based on the HTTP request.
     *
     * @param request the validated inbound DTO
     * @return the persisted Task (PENDING or SCHEDULED status)
     */
    @Transactional
    public Task submit(SubmitTaskRequest request) {
        // ── Idempotency check ────────────────────────────────────────────────
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return repository
                    .findByIdempotencyKey(request.idempotencyKey())
                    .orElseGet(() -> createAndSave(request));
        }
        // ── No idempotency key — always create ───────────────────────────────
        return createAndSave(request);
    }

    private Task createAndSave(SubmitTaskRequest request) {
        Instant now         = Instant.now(clock);
        Instant scheduledAt = request.scheduledAt() != null
                ? request.scheduledAt()
                : now;

        // If scheduledAt is in the future, task starts as SCHEDULED; else PENDING
        TaskStatus initialStatus = scheduledAt.isAfter(now)
                ? TaskStatus.SCHEDULED
                : TaskStatus.PENDING;

        int maxAttempts = request.maxAttempts() != null ? request.maxAttempts() : DEFAULT_MAX_ATTEMPTS;
        int priority    = request.priority()    != null ? request.priority()    : DEFAULT_PRIORITY;

        Task task = Task.reconstitute(
                UUID.randomUUID().toString(),
                request.type(),
                request.payload().toString(),
                initialStatus,
                0,
                maxAttempts,
                now,
                scheduledAt,
                priority,
                null);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            repository.saveWithIdempotencyKey(task, request.idempotencyKey());
        } else {
            repository.save(task);
        }

        // Publish in-process event for listeners (Phase 3 adds metrics listener)
        events.publishEvent(new TaskSubmittedEvent(task.id(), task.type(), now));

        return task;
    }
}
