package com.taskqueue.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Task {
    private final String id;
    private final String type;
    private final String payload;
    private final TaskStatus status;
    private final int attempts;
    private final int maxAttempts;
    private final Instant createdAt;
    private final Instant scheduledAt;
    private final int priority;
    private final String lastError;

    // Transition Graph
    private static final Map<TaskStatus, Set<TaskStatus>> LEGAL = Map.of(TaskStatus.PENDING,
            EnumSet.of(TaskStatus.SCHEDULED, TaskStatus.RUNNING),
            TaskStatus.SCHEDULED, EnumSet.of(TaskStatus.RUNNING, TaskStatus.PENDING),
            TaskStatus.RUNNING, EnumSet.of(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.RETRYING),
            TaskStatus.RETRYING, EnumSet.of(TaskStatus.SCHEDULED, TaskStatus.RUNNING, TaskStatus.DEAD),
            TaskStatus.FAILED, EnumSet.of(TaskStatus.RETRYING, TaskStatus.DEAD),
            TaskStatus.SUCCEEDED, EnumSet.noneOf(TaskStatus.class), // terminal
            TaskStatus.DEAD, EnumSet.noneOf(TaskStatus.class) // terminal
    );

    private Task(
            String id,
            String type,
            String payload,
            TaskStatus status,
            int attempts,
            int maxAttempts,
            int priority,
            Instant createdAt,
            Instant scheduledAt,
            String lastError) {

        this.id = requireText(id, "id");
        this.type = requireText(type, "type");
        this.payload = requireText(payload, "payload");

        this.status = Objects.requireNonNull(status, "status");

        this.attempts = requireNonNegative(attempts, "attempts");
        this.maxAttempts = requirePositive(maxAttempts, "maxAttempts");

        if (attempts > maxAttempts) {
            throw new IllegalArgumentException(
                    "attempts (" + attempts + ") cannot exceed maxAttempts (" + maxAttempts + ")");
        }

        this.priority = requireNonNegative(priority, "priority");

        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");

        if (scheduledAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "scheduledAt cannot be before createdAt");
        }

        this.lastError = lastError;
    }

    /** Factory enforces a born-consistent object; the only way to build a Task. */
    public static Task create(String id, String type, String payload, int maxAttempts, int priority) {
        Instant now = Instant.now();

        return new Task(
                id,
                type,
                payload,
                TaskStatus.PENDING,
                0,
                maxAttempts,
                priority,
                now,
                now,
                null);
    }

    /**
     * Reconstitution factory used exclusively by the persistence layer to
     * rebuild a {@code Task} from a database row.
     *
     * <p>This bypasses the FSM constraints because the database already holds
     * a valid state — we trust the DB as the source of truth here.
     * Application code must still use {@link #create} and transition methods.
     *
     * @param id           stored task id
     * @param type         stored task type
     * @param payload      stored JSON payload (as a String)
     * @param status       stored status
     * @param attempts     stored attempts count
     * @param maxAttempts  stored max attempts
     * @param createdAt    stored creation timestamp
     * @param scheduledAt  stored scheduled timestamp
     * @param priority     stored priority
     * @param lastError    stored last error message (may be null)
     * @return a Task reflecting the persisted state
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static Task reconstitute(
            @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
            @com.fasterxml.jackson.annotation.JsonProperty("type") String type,
            @com.fasterxml.jackson.annotation.JsonProperty("payload") String payload,
            @com.fasterxml.jackson.annotation.JsonProperty("status") TaskStatus status,
            @com.fasterxml.jackson.annotation.JsonProperty("attempts") int attempts,
            @com.fasterxml.jackson.annotation.JsonProperty("maxAttempts") int maxAttempts,
            @com.fasterxml.jackson.annotation.JsonProperty("createdAt") Instant createdAt,
            @com.fasterxml.jackson.annotation.JsonProperty("scheduledAt") Instant scheduledAt,
            @com.fasterxml.jackson.annotation.JsonProperty("priority") int priority,
            @com.fasterxml.jackson.annotation.JsonProperty("lastError") String lastError) {
        Instant safeScheduledAt = (scheduledAt != null && createdAt != null && scheduledAt.isBefore(createdAt))
                ? createdAt
                : scheduledAt;
        return new Task(id, type, payload, status, attempts, maxAttempts, priority,
                createdAt, safeScheduledAt, lastError);
    }

    // ---- read-only accessors ----
    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String payload() {
        return payload;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public int priority() {
        return priority;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaskStatus status() {
        return status;
    } // returns an immutable enum; safe to expose

    public int attempts() {
        return attempts;
    }

    public Instant scheduledAt() {
        return scheduledAt;
    }

    public String lastError() {
        return lastError;
    }

    // ---- the single mutation gate ----
    private Task transitionTo(
            TaskStatus next,
            int attempts,
            Instant scheduledAt,
            String lastError) {
        Set<TaskStatus> allowed = LEGAL.get(status);
        if (!allowed.contains(next)) {
            throw new IllegalStateException(
                    "Illegal transition " + status + " -> " + next + " for task " + id);
        }
        return new Task(
                id,
                type,
                payload,
                next,
                attempts,
                maxAttempts,
                priority,
                createdAt,
                scheduledAt,
                lastError);
    }

    // ---- named business operations (the public surface workers/handlers call) ----

    /** A worker begins executing. Consumes one attempt. */
    public Task markRunning() {
        if (attempts >= maxAttempts) {
            throw new IllegalStateException(
                    "No attempts left (" + attempts + "/" + maxAttempts + ") for task " + id);
        }
        return transitionTo(
                TaskStatus.RUNNING,
                attempts + 1,
                scheduledAt,
                null);
    }

    /** Execution finished successfully. */
    public Task recordSuccess() {
        return transitionTo(
                TaskStatus.SUCCEEDED,
                attempts,
                scheduledAt,
                null);
    }

    /**
     * Execution failed. The Task itself decides the resulting state from its own
     * invariants:
     * retry if budget remains and the failure is retryable, otherwise dead-letter.
     */
    public Task recordFailure(String error, boolean retryable) {
        Task failed = transitionTo(
                TaskStatus.FAILED,
                attempts,
                scheduledAt,
                error);
        if (retryable && attempts < maxAttempts) {
            return failed.transitionTo(
                    TaskStatus.RETRYING,
                    failed.attempts(),
                    failed.scheduledAt(),
                    error);
        } else {
            return failed.transitionTo(
                    TaskStatus.DEAD,
                    failed.attempts(),
                    failed.scheduledAt(),
                    error);
        }
    }

    /** Scheduler will run this later; mutator validated and clock-aware. */
    public Task scheduleAt(Instant when) {
        Objects.requireNonNull(when, "when");
        return transitionTo(
                TaskStatus.SCHEDULED,
                attempts,
                when,
                lastError);
    }

    /**
     * Transitions a RUNNING task to RETRYING with a future scheduled time.
     *
     * <p>Called by {@code RetryHandler} after a retryable failure. The task is
     * persisted in RETRYING state with {@code scheduledAt = now + backoffDelay}.
     * The next worker poll will pick it up only after that time has passed.
     *
     * <p>This is the key mechanism that prevents Thread.sleep in workers:
     * the queue owns the delay, not the worker thread.
     *
     * @param retryAt the future time when this task should be retried
     * @param error   the error message from the failed attempt
     * @return a new Task in RETRYING status with updated scheduledAt
     */
    public Task scheduleRetryAt(java.time.Instant retryAt, String error) {
        Objects.requireNonNull(retryAt, "retryAt");
        Task failed = transitionTo(TaskStatus.FAILED, attempts, scheduledAt, error);
        return failed.transitionTo(TaskStatus.RETRYING, failed.attempts(), retryAt, error);
    }

    public boolean isTerminal() {
        return status == TaskStatus.SUCCEEDED || status == TaskStatus.DEAD;
    }

    public boolean canRetry() {
        return attempts < maxAttempts;
    }

    /** Copy-on-change: returns a new Task with a different priority. Used in tests. */
    public Task withPriority(int newPriority) {
        return new Task(id, type, payload, status, attempts, maxAttempts, newPriority,
                createdAt, scheduledAt, lastError);
    }

    // two tasks are equals if and only if they share same id
    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof Task other))
            return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Task[id=%s, type=%s, status=%s, attempts=%d/%d, priority=%d]"
                .formatted(id, type, status, attempts, maxAttempts, priority);
    }

    // ----------validators-------------------
    private static int requirePositive(int v, String name) {
        if (v < 1)
            throw new IllegalArgumentException(name + " must be >= 1");
        return v;
    }

    private static int requireNonNegative(int v, String name) {
        if (v < 0)
            throw new IllegalArgumentException(name + " must be >= 0");
        return v;
    }

    private static String requireText(String v, String name) {
        if (v == null || v.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
        return v;
    }
}
