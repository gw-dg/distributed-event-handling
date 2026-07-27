package com.taskqueue.events;

import java.time.Instant;

/** Published when a task handler returns a successful result. */
public record TaskSucceededEvent(
        String taskId,
        String type,
        Instant occurredAt) {
}
