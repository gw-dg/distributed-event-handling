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
 * <p>From ch03 material: prefer {@code @ConfigurationProperties} over many {@code @Value}
 * fields for any group of related settings.
 *
 * <p>Example YAML binding:
 * <pre>
 * taskqueue:
 *   queue:
 *     type: postgres
 *     capacity: 10000
 *     poll-batch-size: 25
 *   workers:
 *     count: 4
 *     shutdown-timeout: 30s
 *   retry:
 *     max-attempts: 5
 *     base-delay: 1s
 *     max-delay: 5m
 *     jitter: true
 *   reaper:
 *     interval: 30s
 *     visibility-timeout: 5m
 * </pre>
 */
@ConfigurationProperties(prefix = "taskqueue")
public record TaskQueueProperties(
        Queue queue,
        Workers workers,
        Retry retry,
        Reaper reaper) {

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
}
