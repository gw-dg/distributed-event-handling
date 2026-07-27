package com.taskqueue.events;

import java.time.Instant;

/** Published when a task execution fails (retryable or permanent). */
public record TaskFailedEvent(
        String taskId,
        String type,
        String reason,
        boolean retryable,
        Instant occurredAt) {
}
