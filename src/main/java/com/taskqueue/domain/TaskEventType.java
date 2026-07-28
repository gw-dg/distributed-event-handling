package com.taskqueue.domain;

/**
 * All lifecycle events that can occur on a task.
 *
 * <p>From observer.md: "Events become first-class data instead of buried log lines.
 * Subscribers decide what to do with them — metrics, audit, DLQ forwarding, etc."
 *
 * <p>Lifecycle flow (happy path):
 * <pre>
 *   SUBMITTED → STARTED → SUCCEEDED
 * </pre>
 * Failure paths:
 * <pre>
 *   STARTED → RETRY_SCHEDULED → STARTED (repeat up to maxAttempts)
 *          → DEAD_LETTERED
 * </pre>
 */
public enum TaskEventType {

    /** Task was accepted and written to the outbox / enqueued. */
    SUBMITTED,

    /** A worker has claimed the task and begun executing its handler. */
    STARTED,

    /** Handler returned success. Task is now in SUCCEEDED state. */
    SUCCEEDED,

    /** Handler returned a permanent (non-retryable) failure. */
    FAILED,

    /** Handler returned a retryable failure; task has been re-queued after backoff. */
    RETRY_SCHEDULED,

    /** Task exhausted all retry attempts or encountered a permanent failure; moved to DLQ. */
    DEAD_LETTERED
}
