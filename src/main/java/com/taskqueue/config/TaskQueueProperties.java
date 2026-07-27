package com.taskqueue.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the Task Queue.
 *
 * <p>Bound from {@code application.yml} under the {@code taskqueue} prefix.
 * Using a typed record instead of scattered {@code @Value} fields means:
 * <ul>
 *   <li>All settings are visible in one place.</li>
 *   <li>IDE autocompletion works (via spring-boot-configuration-processor).</li>
 *   <li>Type conversion from YAML strings to Duration, int, boolean is automatic.</li>
 * </ul>
 *
 * <p>Phase 3 additions: {@link RateLimit} and {@link Resilience} sub-records.
 *
 * <p>From ch03 material: prefer {@code @ConfigurationProperties} over many {@code @Value}
 * fields for any group of related settings.
 */
@ConfigurationProperties(prefix = "taskqueue")
public record TaskQueueProperties(
        Queue queue,
        Workers workers,
        Retry retry,
        Reaper reaper,
        RateLimit rateLimit,
        Resilience resilience) {

    /** Queue-level settings. */
    public record Queue(
            /** "memory" or "postgres" — selects the TaskQueue bean via @ConditionalOnProperty. */
            String type,
            /** Max tasks in the in-memory buffer (InMemoryTaskQueue capacity). */
            int capacity,
            /** How many rows PostgresTaskQueue polls per SKIP LOCKED batch. */
            int pollBatchSize) {
    }

    /** Worker pool settings. */
    public record Workers(
            /** Number of concurrent worker threads. */
            int count,
            /** How long to wait for workers to finish on graceful shutdown. */
            Duration shutdownTimeout) {
    }

    /** Retry policy settings — wired into ExponentialBackoffRetryPolicy. */
    public record Retry(
            /** Maximum number of total attempts (first + retries). */
            int maxAttempts,
            /** Base delay for exponential backoff (e.g., PT1S). */
            Duration baseDelay,
            /** Cap on any single retry delay (e.g., PT5M). */
            Duration maxDelay,
            /** If true, adds random jitter to prevent thundering herd. */
            boolean jitter) {
    }

    /** Stuck-task reaper settings. */
    public record Reaper(
            /** How often the reaper polls for stuck tasks. */
            Duration interval,
            /** Tasks in RUNNING state older than this are considered stuck. */
            Duration visibilityTimeout) {
    }

    /**
     * Token Bucket rate limiter settings (Phase 3).
     *
     * <p>Example YAML:
     * <pre>
     * taskqueue:
     *   rate-limit:
     *     capacity: 100
     *     refill-rate: 10
     * </pre>
     */
    public record RateLimit(
            /** Maximum tokens the bucket can hold (also the burst size). */
            long capacity,
            /** Tokens added per second (sustained throughput). */
            long refillRate) {
    }

    /**
     * Circuit breaker settings (Phase 3).
     *
     * <p>Example YAML:
     * <pre>
     * taskqueue:
     *   resilience:
     *     sliding-window-size: 10
     *     failure-rate-threshold: 50
     *     wait-duration-open: PT10S
     * </pre>
     */
    public record Resilience(
            /** Number of calls in the sliding window for failure rate calculation. */
            int slidingWindowSize,
            /** Failure percentage (0–100) that trips the breaker to OPEN. */
            float failureRateThreshold,
            /** How long to wait in OPEN state before probing (HALF_OPEN). */
            Duration waitDurationOpen) {
    }
}
