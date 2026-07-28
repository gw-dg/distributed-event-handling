package com.taskqueue.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.redis.testcontainers.RedisContainer;
import com.taskqueue.domain.TaskEvent;
import com.taskqueue.domain.TaskEventType;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for cross-node event delivery via {@link RedisEventBus}.
 *
 * <p>Simulates two nodes (two EventBus instances pointing at the same Redis):
 * <ul>
 *   <li>Node A publishes 20 events.</li>
 *   <li>Node B (different EventBus instance) receives all 20 via Redis pub/sub.</li>
 * </ul>
 *
 * <p>From observer.md + message-queues.md: "Redis pub/sub delivers to all subscribers
 * on all nodes subscribed to the channel at the time of publish."
 */
@Testcontainers
class EventBusIT {

    @Container
    static final RedisContainer redis = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.2.4"));

    private StringRedisTemplate template;
    private ObjectMapper mapper;
    private RedisMessageListenerContainer container;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getFirstMappedPort());
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();

        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.start();

        template.getConnectionFactory().getConnection().flushAll();
    }

    @AfterEach
    void tearDown() {
        container.stop();
        template.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void publishOnNodeA_receivedByNodeBSubscriber() throws Exception {
        String channel = "test-events";

        // Node B: subscribe first (must be ready before Node A publishes)
        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        RedisEventBus nodeB = new RedisEventBus(template, mapper, channel, container);
        nodeB.subscribe(received::add);

        // Small pause to ensure the Redis subscription is registered
        Thread.sleep(200);

        // Node A: publish 20 events
        RedisMessageListenerContainer containerA = new RedisMessageListenerContainer();
        containerA.setConnectionFactory(template.getConnectionFactory());
        containerA.afterPropertiesSet();
        containerA.start();

        RedisEventBus nodeA = new RedisEventBus(template, mapper, channel, containerA);
        int eventCount = 20;
        for (int i = 0; i < eventCount; i++) {
            nodeA.publish(TaskEvent.submitted("task-" + i, "email"));
        }

        // Node B should receive all events
        await().atMost(Duration.ofSeconds(5))
               .until(() -> received.size() == eventCount);

        assertThat(received).hasSize(eventCount);
        assertThat(received)
                .allMatch(e -> e.eventType() == TaskEventType.SUBMITTED);

        containerA.stop();
    }

    @Test
    void subscriberException_doesNotBlockOtherSubscribers() throws Exception {
        String channel = "test-exception-channel";

        List<TaskEvent> receivedByGood = new CopyOnWriteArrayList<>();

        RedisEventBus bus = new RedisEventBus(template, mapper, channel, container);
        // Bad subscriber that throws
        bus.subscribe(e -> { throw new RuntimeException("Bad subscriber!"); });
        // Good subscriber after the bad one
        bus.subscribe(receivedByGood::add);

        Thread.sleep(200);

        bus.publish(TaskEvent.submitted("task-exc", "email"));

        await().atMost(Duration.ofSeconds(3))
               .until(() -> receivedByGood.size() == 1);

        assertThat(receivedByGood).hasSize(1);
    }
}
