package com.taskqueue.port;

import com.taskqueue.domain.TaskEvent;

/**
 * Observer subscriber contract for the EventBus.
 *
 * <p>From observer.md: "Any subscriber implements this single method.
 * The EventBus calls it for every published event."
 *
 * <p>From ch08 (functional interfaces): implemented as a {@code @FunctionalInterface}
 * so simple subscribers can be registered as lambdas.
 *
 * <p>Implementations: {@link com.taskqueue.events.MetricsEventListener},
 * {@link com.taskqueue.event.AuditEventListener}.
 */
@FunctionalInterface
public interface TaskEventListener {

    /**
     * Receives a task lifecycle event.
     *
     * <p>Implementations MUST NOT throw unchecked exceptions — the EventBus
     * catches per-listener exceptions and logs them so one bad subscriber
     * cannot block the others.
     *
     * @param event the event; never null
     */
    void onEvent(TaskEvent event);
}
