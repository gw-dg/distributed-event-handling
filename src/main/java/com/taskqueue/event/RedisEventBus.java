package com.taskqueue.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.domain.TaskEvent;
import com.taskqueue.port.EventBus;
import com.taskqueue.port.TaskEventListener;

/**
 * Multi-node {@link EventBus} backed by Redis pub/sub.
 *
 * <p>From observer.md + message-queues.md: "Redis pub/sub is fire-and-forget.
 * It is correct for metrics and audit (best-effort delivery) but wrong for the
 * task pipeline itself (no persistence, no consumer group, no replay)."
 *
 * <p>Architecture:
 * <ul>
 *   <li>On {@link #publish}: {@code PUBLISH task-events &lt;json&gt;} — Redis fans out to all
 *       nodes subscribed to the channel.</li>
 *   <li>On {@link #subscribe}: registers a {@link MessageListener} in the
 *       {@link RedisMessageListenerContainer}, which delivers the message to all local
 *       listeners on this node.</li>
 * </ul>
 *
 * <p>Trade-off: if a subscriber node is down at publish time, it misses the event.
 * This is acceptable for metrics/audit (a gap is tolerable) but not for task delivery
 * (which uses Redis Streams + the PEL for durability).
 */
public class RedisEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(RedisEventBus.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final String channel;
    private final RedisMessageListenerContainer listenerContainer;
    private final List<TaskEventListener> localListeners = new CopyOnWriteArrayList<>();

    public RedisEventBus(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            String channel,
            RedisMessageListenerContainer listenerContainer) {
        this.redis             = redis;
        this.mapper            = mapper;
        this.channel           = channel;
        this.listenerContainer = listenerContainer;

        // Register a single Redis MessageListener that fans out to all local listeners
        listenerContainer.addMessageListener(this::onRedisMessage, new ChannelTopic(channel));
    }

    /**
     * Publishes by serialising the event to JSON and calling Redis PUBLISH.
     * Fire-and-forget — exceptions are logged but not propagated.
     */
    @Override
    public void publish(TaskEvent event) {
        try {
            String json = mapper.writeValueAsString(event);
            redis.convertAndSend(channel, json);
        } catch (Exception e) {
            log.error("[RedisEventBus] Failed to publish event {}: {}", event.eventId(), e.getMessage());
        }
    }

    /** Adds a local listener that will be invoked for every message received on the channel. */
    @Override
    public void subscribe(TaskEventListener listener) {
        localListeners.add(listener);
    }

    // ── Redis MessageListener (called by the container's listener thread) ──────

    private void onRedisMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            TaskEvent event = mapper.readValue(json, TaskEvent.class);
            for (TaskEventListener listener : localListeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                    log.error("[RedisEventBus] Listener {} threw for event {}: {}",
                            listener.getClass().getSimpleName(), event.eventId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[RedisEventBus] Failed to deserialize message: {}", e.getMessage());
        }
    }
}
