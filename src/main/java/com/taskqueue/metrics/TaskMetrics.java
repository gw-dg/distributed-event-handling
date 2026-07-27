package com.taskqueue.metrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.taskqueue.repo.DeadLetterRepository;
import com.taskqueue.repo.TaskRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer-based metrics for the task queue.
 *
 * <p>From chapter-07-observability-security-and-production.md:
 *   "Metrics: submitted count, terminal count by status, retry count, DLQ count,
 *    execution duration, queue depth, oldest pending age."
 *
 * <h2>Metric naming convention</h2>
 * Follows Micrometer/Prometheus convention: {@code taskqueue_<noun>_<unit>}.
 * All per-type breakdowns use the {@code type} tag so dashboards can
 * filter by handler type. Tag cardinality is bounded (finite set of task types).
 *
 * <h2>Gauges vs counters</h2>
 * <ul>
 *   <li>Counters: monotonically increasing — submitted, succeeded, failed, dead.
 *   <li>Timers: measure execution duration per task type.
 *   <li>Gauges: point-in-time snapshot — queue depth, DLQ size.
 *       Gauges are registered once and pull the value lazily on each scrape.
 * </ul>
 */
@Component
public class TaskMetrics {

    // ── Counters ──────────────────────────────────────────────────────────────
    private final Counter submittedTotal;
    private final Counter retriedTotal;

    // ── Per-type caches (bounded by number of registered handler types) ───────
    private final ConcurrentMap<String, Counter> succeededByType  = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> failedByType     = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> deadByType       = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer>   timerByType      = new ConcurrentHashMap<>();

    private final MeterRegistry registry;

    public TaskMetrics(
            MeterRegistry registry,
            TaskRepository taskRepository,
            DeadLetterRepository dlqRepository) {

        this.registry = registry;

        // ── Counters (global) ────────────────────────────────────────────────
        this.submittedTotal = Counter.builder("taskqueue.tasks.submitted")
                .description("Total tasks submitted via the API")
                .register(registry);

        this.retriedTotal = Counter.builder("taskqueue.tasks.retried")
                .description("Total task retry attempts scheduled")
                .register(registry);

        // ── Gauges (point-in-time, polled on each Prometheus scrape) ─────────
        // queue.depth: how many tasks are PENDING or RETRYING right now
        registry.gauge("taskqueue.queue.depth",
                taskRepository,
                TaskRepository::pendingCount);

        // dlq.size: how many entries are in the dead_letter table
        registry.gauge("taskqueue.dlq.size",
                dlqRepository,
                dlq -> (double) dlq.count());
    }

    // ── Increment helpers (called by MetricsEventListener) ───────────────────

    /** Increments the global submitted counter (one call per task submission). */
    public void recordSubmitted() {
        submittedTotal.increment();
    }

    /** Increments succeeded counter for the given task type. */
    public void recordSucceeded(String type) {
        succeededCounter(type).increment();
    }

    /** Increments failed (non-terminal) counter for the given task type. */
    public void recordFailed(String type) {
        failedCounter(type).increment();
    }

    /** Increments dead (terminal failure) counter for the given task type. */
    public void recordDead(String type) {
        deadCounter(type).increment();
    }

    /** Increments the global retry counter. */
    public void recordRetried() {
        retriedTotal.increment();
    }

    /**
     * Records task execution duration for the given type.
     *
     * @param type         task type
     * @param durationMs   execution time in milliseconds
     */
    public void recordExecutionTime(String type, long durationMs) {
        executionTimer(type).record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ── Lazy per-type meter factories ─────────────────────────────────────────

    private Counter succeededCounter(String type) {
        return succeededByType.computeIfAbsent(type, t ->
                Counter.builder("taskqueue.tasks.succeeded")
                        .description("Tasks completed successfully")
                        .tag("type", t)
                        .register(registry));
    }

    private Counter failedCounter(String type) {
        return failedByType.computeIfAbsent(type, t ->
                Counter.builder("taskqueue.tasks.failed")
                        .description("Task execution failures (includes retried)")
                        .tag("type", t)
                        .register(registry));
    }

    private Counter deadCounter(String type) {
        return deadByType.computeIfAbsent(type, t ->
                Counter.builder("taskqueue.tasks.dead")
                        .description("Tasks permanently failed and dead-lettered")
                        .tag("type", t)
                        .register(registry));
    }

    private Timer executionTimer(String type) {
        return timerByType.computeIfAbsent(type, t ->
                Timer.builder("taskqueue.task.execution.duration")
                        .description("Task execution duration")
                        .tag("type", t)
                        .register(registry));
    }
}
