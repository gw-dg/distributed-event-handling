package com.taskqueue.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.taskqueue.metrics.TaskMetrics;

/**
 * Decoupled metrics listener — subscribes to Spring task lifecycle events and
 * updates {@link TaskMetrics} counters/timers without touching the Worker or
 * RetryHandler.
 *
 * <p>From chapter-07 + observer pattern:
 *   "Observer (Listener) decouples metric recording from business logic.
 *    The Worker publishes events; it has no knowledge of who listens.
 *    This means metrics can be added, removed, or changed without touching
 *    the worker loop."
 *
 * <p>{@code @Async} is NOT used here intentionally — metric recording is fast
 * (nanosecond counter increments) and synchronous publishing avoids the risk of
 * losing events if the async executor is overloaded or shut down.
 */
@Component
public class MetricsEventListener {

    private static final Logger log = LoggerFactory.getLogger(MetricsEventListener.class);

    private final TaskMetrics metrics;

    public MetricsEventListener(TaskMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Called when a task is accepted via the REST API.
     * Increments the global submitted counter.
     */
    @EventListener
    public void onSubmitted(TaskSubmittedEvent event) {
        metrics.recordSubmitted();
        log.debug("Metrics: task submitted type={} id={}", event.type(), event.taskId());
    }

    /**
     * Called when a task handler returns successfully.
     * Increments the per-type succeeded counter.
     */
    @EventListener
    public void onSucceeded(TaskSucceededEvent event) {
        metrics.recordSucceeded(event.type());
    }

    /**
     * Called when a task handler returns a failure result or throws.
     * Differentiates between retryable (retry counter) and permanent (dead counter).
     */
    @EventListener
    public void onFailed(TaskFailedEvent event) {
        metrics.recordFailed(event.type());
        if (!event.retryable()) {
            // Permanent failure → task is dead-lettered
            metrics.recordDead(event.type());
        } else {
            metrics.recordRetried();
        }
    }
}
