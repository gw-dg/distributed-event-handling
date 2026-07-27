package com.taskqueue.web;

import java.time.Instant;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

/**
 * Outbound DTO for task responses.
 *
 * <p>From coupling.md: "Don't expose domain objects over HTTP."
 * This DTO controls the public API contract independently of the internal
 * {@link Task} domain object. Adding an internal field to {@code Task} does
 * not automatically expose it to API clients.
 *
 * <p>Fields exposed: id, type, status, attempts, maxAttempts, createdAt, scheduledAt, priority.
 *
 * <p>Fields deliberately omitted from the response:
 * <ul>
 *   <li>{@code payload} — can be large; clients already have it
 *   <li>{@code lastError} — internal debugging field; expose only on error endpoints
 *   <li>{@code version} — internal optimistic locking counter
 * </ul>
 */
public record TaskResponse(
        String id,
        String type,
        TaskStatus status,
        int attempts,
        int maxAttempts,
        Instant createdAt,
        Instant scheduledAt,
        int priority) {

    /**
     * Maps a domain {@link Task} to this response DTO.
     *
     * <p>This is the only translation point. If the response contract changes,
     * only this method needs updating — not the Task domain object or the controller.
     */
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.id(),
                task.type(),
                task.status(),
                task.attempts(),
                task.maxAttempts(),
                task.createdAt(),
                task.scheduledAt(),
                task.priority());
    }
}
