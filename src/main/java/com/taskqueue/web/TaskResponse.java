package com.taskqueue.web;

import java.time.Instant;

import com.taskqueue.model.Task;
import com.taskqueue.model.TaskStatus;

/**
 * Outbound DTO for task responses.
 *
 * <p>Phase 3: added {@code payload} and {@code lastError} so the frontend
 * task drawer can show full details without a second request. In a high-volume
 * production API you might split these into a separate detail endpoint, but for
 * the dashboard use case the overhead is acceptable.
 */
public record TaskResponse(
        String id,
        String type,
        TaskStatus status,
        int attempts,
        int maxAttempts,
        Instant createdAt,
        Instant scheduledAt,
        int priority,
        String payload,
        String lastError) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.id(),
                task.type(),
                task.status(),
                task.attempts(),
                task.maxAttempts(),
                task.createdAt(),
                task.scheduledAt(),
                task.priority(),
                task.payload(),
                task.lastError());
    }
}
