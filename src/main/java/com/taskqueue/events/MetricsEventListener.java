package com.taskqueue.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taskqueue.domain.TaskEvent;
import com.taskqueue.domain.TaskEventType;
import com.taskqueue.metrics.TaskMetrics;
import com.taskqueue.port.TaskEventListener;

/**
 * Decoupled metrics subscriber — receives {@link TaskEvent}s from the {@link com.taskqueue.port.EventBus}
 * and updates {@link TaskMetrics} counters/timers.
 *
 * <p>Phase 4 update: now implements {@link TaskEventListener} (the EventBus port) instead of
 * using Spring's {@code @EventListener}. This makes it work both in single-node mode
 * (InProcessEventBus) and across nodes (RedisEventBus).
 *
 * <p>From observer.md: "Observer decouples metric recording from business logic.
 * The Worker publishes events; it has no knowledge of who listens."
 *
 * <p>Metric recording is nanosecond-fast — synchronous delivery is intentional.
 */
public class MetricsEventListener implements TaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(MetricsEventListener.class);

    private final TaskMetrics metrics;

    public MetricsEventListener(TaskMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onEvent(TaskEvent event) {
        TaskEventType type = event.eventType();
        switch (type) {
            case SUBMITTED     -> {
                metrics.recordSubmitted();
                log.debug("Metrics: task submitted type={} id={}", event.taskType(), event.taskId());
            }
            case SUCCEEDED     -> metrics.recordSucceeded(event.taskType());
            case FAILED        -> {
                metrics.recordFailed(event.taskType());
                metrics.recordDead(event.taskType());
            }
            case RETRY_SCHEDULED -> {
                metrics.recordFailed(event.taskType());
                metrics.recordRetried();
            }
            case DEAD_LETTERED -> metrics.recordDead(event.taskType());
            case STARTED       -> { /* no counter for started yet */ }
        }
    }

    @org.springframework.context.event.EventListener
    public void onTaskSubmitted(TaskSubmittedEvent event) {
        metrics.recordSubmitted();
    }

    @org.springframework.context.event.EventListener
    public void onTaskSucceeded(TaskSucceededEvent event) {
        metrics.recordSucceeded(event.type());
    }

    @org.springframework.context.event.EventListener
    public void onTaskFailed(TaskFailedEvent event) {
        metrics.recordFailed(event.type());
    }
}
