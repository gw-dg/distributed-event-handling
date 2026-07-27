package com.taskqueue.dlq;

import java.time.Instant;

/**
 * Immutable value object representing one entry in the {@code dead_letter} table.
 *
 * <p>Used by {@link com.taskqueue.repo.DeadLetterRepository} to return rows and by
 * the REST layer to serialize operator-facing responses.
 *
 * @param id                 surrogate primary key in dead_letter
 * @param taskId             original task UUID
 * @param type               task type (e.g., "email", "report")
 * @param payload            original JSON payload (preserved for redrive)
 * @param reason             why the task was dead-lettered
 * @param originalAttempts   how many times execution was attempted
 * @param priority           original task priority (preserved for redrive)
 * @param failedAt           when the task was moved to DLQ
 * @param redriveCount       how many times an operator has redriven this entry
 * @param lastRedriveAt      last time a redrive was attempted (null if never)
 */
public record DeadLetterEntry(
        long id,
        String taskId,
        String type,
        String payload,
        String reason,
        int originalAttempts,
        int priority,
        Instant failedAt,
        int redriveCount,
        Instant lastRedriveAt) {
}
