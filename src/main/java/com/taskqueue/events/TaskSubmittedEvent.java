package com.taskqueue.events;

import java.time.Instant;

/**
 * Spring application event published when a task is successfully submitted.
 *
 * <p>From ch06: "Spring events for in-process lifecycle notifications."
 * Listeners (e.g., a metrics listener in Phase 3) can subscribe via
 * {@code @EventListener} without coupling the submission service to them.
 *
 * <p>This is an in-process event — it does NOT cross process boundaries.
 * Phase 4 will publish these to a broker (Kafka/RabbitMQ) for distributed consumers.
 */
public record TaskSubmittedEvent(
        String taskId,
        String type,
        Instant occurredAt) {
}
