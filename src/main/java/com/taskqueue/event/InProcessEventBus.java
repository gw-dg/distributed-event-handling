package com.taskqueue.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taskqueue.domain.TaskEvent;
import com.taskqueue.port.EventBus;
import com.taskqueue.port.TaskEventListener;

/**
 * Single-node, synchronous {@link EventBus} backed by a {@link CopyOnWriteArrayList}.
 *
 * <p>From observer.md + concurrent-collections.md: "CopyOnWriteArrayList is the
 * right structure when reads (fan-out) vastly outnumber writes (subscribe). The list
 * is never modified during iteration — the snapshot is consistent without locking."
 *
 * <p>Used in:
 * <ul>
 *   <li>Integration tests (no Redis required).</li>
 *   <li>Single-node local development ({@code EVENT_BUS_TYPE=in-process}).</li>
 * </ul>
 *
 * <p>Exceptions from one subscriber are caught and logged so they cannot prevent
 * delivery to subsequent subscribers.
 */
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final List<TaskEventListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(TaskEvent event) {
        for (TaskEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("[EventBus] Listener {} threw exception for event {}: {}",
                        listener.getClass().getSimpleName(), event.eventId(), e.getMessage());
            }
        }
    }

    @Override
    public void subscribe(TaskEventListener listener) {
        listeners.add(listener);
        log.debug("[EventBus] Registered listener: {}", listener.getClass().getSimpleName());
    }

    public int subscriberCount() {
        return listeners.size();
    }
}
