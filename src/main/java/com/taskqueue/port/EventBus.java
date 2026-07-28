package com.taskqueue.port;

import com.taskqueue.domain.TaskEvent;

/**
 * Inbound port for the event bus — the Observer pattern applied at the system boundary.
 *
 * <p>From observer.md + hexagonal-architecture.md: publishers depend on this interface,
 * not on any specific messaging technology. Implementations:
 * <ul>
 *   <li>{@link com.taskqueue.event.InProcessEventBus} — synchronous in-JVM fan-out
 *       (single-node dev and tests)</li>
 *   <li>{@link com.taskqueue.event.RedisEventBus} — Redis pub/sub cross-node fan-out</li>
 * </ul>
 *
 * <p>From mediator.md: the EventBus plays the Mediator role — publishers never know
 * which subscribers exist. Adding or removing a subscriber never touches the publisher.
 */
public interface EventBus {

    /**
     * Publishes an event to all registered subscribers.
     *
     * <p>Implementations MUST NOT throw exceptions — a bus publish failure must not
     * propagate back to the worker or web thread. Log and swallow any errors.
     *
     * @param event the event to broadcast; never null
     */
    void publish(TaskEvent event);

    /**
     * Registers a subscriber to receive all future events.
     *
     * <p>For the Redis implementation, local-node registration is sufficient —
     * the Redis channel delivers to all nodes.
     *
     * @param listener the subscriber; never null
     */
    void subscribe(TaskEventListener listener);
}
