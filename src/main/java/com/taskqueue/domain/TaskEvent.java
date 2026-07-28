package com.taskqueue.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.taskqueue.model.Task;

/**
 * Immutable domain event describing a lifecycle change on a {@link Task}.
 *
 * <p>From observer.md + domain-modeling.md: events are first-class data. Every
 * state change the {@link com.taskqueue.port.EventBus} carries is captured here.
 * Subscribers ({@link com.taskqueue.event.AuditEventListener},
 * {@link com.taskqueue.events.MetricsEventListener}) receive this record and
 * decide independently what to do with it.
 *
 * <p>This record replaces the three fragmented Phase 3 Spring application events
 * ({@code TaskSubmittedEvent}, {@code TaskSucceededEvent}, {@code TaskFailedEvent})
 * with a unified model that works across node boundaries via Redis pub/sub.
 *
 * @param eventId    unique ID for this event (for dedup / idempotency at the subscriber)
 * @param taskId     the task that changed state
 * @param taskType   the task's type string (e.g., "email", "report")
 * @param eventType  which lifecycle stage this event represents
 * @param status     the task's new status (may be null for SUBMITTED)
 * @param attempts   how many times the task has been attempted (0 for SUBMITTED)
 * @param detail     human-readable message (error text, "duplicate suppressed", etc.)
 * @param occurredAt wall-clock time of the transition
 */
public record TaskEvent(
        String eventId,
        String taskId,
        String taskType,
        TaskEventType eventType,
        String status,
        int attempts,
        String detail,
        Instant occurredAt) {

    public TaskEvent {
        Objects.requireNonNull(eventId,   "eventId");
        Objects.requireNonNull(taskId,    "taskId");
        Objects.requireNonNull(taskType,  "taskType");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt,"occurredAt");
    }

    /**
     * Primary factory — creates a fully populated event from a live {@link Task}.
     *
     * @param task      the task whose state just changed
     * @param eventType which transition occurred
     * @param detail    optional detail message (error text, etc.); may be null
     * @return immutable event ready to publish
     */
    public static TaskEvent of(Task task, TaskEventType eventType, String detail) {
        Objects.requireNonNull(task, "task");
        return new TaskEvent(
                UUID.randomUUID().toString(),
                task.id(),
                task.type(),
                eventType,
                task.status() != null ? task.status().name() : null,
                task.attempts(),
                detail,
                Instant.now());
    }

    /**
     * Convenience factory for SUBMITTED events where we only have id/type
     * (task may not be persisted yet).
     */
    public static TaskEvent submitted(String taskId, String taskType) {
        Objects.requireNonNull(taskId,   "taskId");
        Objects.requireNonNull(taskType, "taskType");
        return new TaskEvent(
                UUID.randomUUID().toString(),
                taskId,
                taskType,
                TaskEventType.SUBMITTED,
                "PENDING",
                0,
                null,
                Instant.now());
    }
}
