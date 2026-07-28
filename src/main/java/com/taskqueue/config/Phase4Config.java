package com.taskqueue.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.taskqueue.broker.redis.RedisStreamDeadLetterQueue;
import com.taskqueue.broker.redis.RedisStreamTaskQueue;
import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.event.AuditEventListener;
import com.taskqueue.event.InProcessEventBus;
import com.taskqueue.event.RedisEventBus;
import com.taskqueue.events.MetricsEventListener;
import com.taskqueue.lock.RedisDistributedLock;
import com.taskqueue.lock.RedisLeaderElector;
import com.taskqueue.metrics.TaskMetrics;
import com.taskqueue.outbox.OutboxRelay;
import com.taskqueue.outbox.OutboxRepository;
import com.taskqueue.port.EventBus;
import com.taskqueue.port.LeaderElector;
import com.taskqueue.queue.TaskQueue;
import com.taskqueue.ratelimit.RateLimiter;
import com.taskqueue.ratelimit.RedisTokenBucketRateLimiter;
import com.taskqueue.repo.TaskRepository;
import com.taskqueue.scheduler.LeaderElectedTaskScheduler;

/**
 * Phase 4 Spring configuration — distributed infrastructure beans.
 *
 * <p>Adds beans for:
 * <ul>
 *   <li>Redis Streams task queue and DLQ</li>
 *   <li>EventBus (in-process or Redis pub/sub, conditional)</li>
 *   <li>Leader election and distributed lock</li>
 *   <li>Outbox relay and leader-elected scheduler</li>
 *   <li>Distributed Redis token bucket rate limiter</li>
 * </ul>
 *
 * <p>All beans are conditional on properties so Phase 3 Postgres-only mode still works.
 */
@Configuration
@EnableScheduling
@EnableTransactionManagement
public class Phase4Config {

    // ── ObjectMapper ─────────────────────────────────────────────────────────

    /**
     * Jackson ObjectMapper with Java time support — used for event serialization.
     * Overrides the auto-configured one to ensure JavaTimeModule is registered.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
                .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.IS_GETTER,
                        com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE);
    }

    // ── Redis Streams Task Queue (Phase 4) ───────────────────────────────────

    @Bean
    @Primary
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public TaskQueue redisStreamTaskQueue(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            TaskQueueProperties props) {
        TaskQueueProperties.Queue q = props.queue();
        return new RedisStreamTaskQueue(redis, mapper, q.consumerGroup(), q.consumerName());
    }

    // ── Redis Streams Dead Letter Queue (Phase 4) ────────────────────────────

    /**
     * Replace the Postgres DLQ with Redis Streams DLQ when Redis is active.
     * Operators read: redis-cli XREAD COUNT 100 STREAMS dlq-stream 0-0
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public DeadLetterQueue redisStreamDeadLetterQueue(
            StringRedisTemplate redis,
            ObjectMapper mapper) {
        return new RedisStreamDeadLetterQueue(redis, mapper);
    }

    // ── Distributed Rate Limiter (Phase 4) ───────────────────────────────────

    /**
     * Replaces the Phase 3 in-JVM token bucket when Redis is active.
     * All nodes share the same bucket key → aggregate rate is correctly capped.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public RateLimiter redisRateLimiter(
            StringRedisTemplate redis,
            TaskQueueProperties props) {
        TaskQueueProperties.RateLimit rl = props.rateLimit();
        return new RedisTokenBucketRateLimiter(redis, "rate-limit:global",
                rl.capacity(), rl.refillRate());
    }

    // ── Leader Election (Phase 4) ─────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public LeaderElector redisLeaderElector(
            StringRedisTemplate redis,
            TaskQueueProperties props) {
        TaskQueueProperties.Leader leader = props.leader();
        String nodeId = leader.nodeId();
        if (nodeId == null || nodeId.isBlank() || "worker-0".equals(nodeId)) {
            nodeId = "node-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
        RedisLeaderElector elector = new RedisLeaderElector(
                redis, nodeId, leader.ttlMs(), leader.heartbeatMs());
        elector.startHeartbeat("outbox-relay");
        elector.startHeartbeat("scheduler");
        return elector;
    }

    // ── Distributed Lock ──────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public RedisDistributedLock redisDistributedLock(StringRedisTemplate redis) {
        return new RedisDistributedLock(redis);
    }

    // ── EventBus (Phase 4) ────────────────────────────────────────────────────

    /** In-process EventBus — active when EVENT_BUS_TYPE=in-process (or not set in tests). */
    @Bean
    @ConditionalOnProperty(name = "taskqueue.event-bus.type",
            havingValue = "in-process", matchIfMissing = false)
    public EventBus inProcessEventBus(
            TaskMetrics taskMetrics,
            JdbcTemplate jdbc) {
        InProcessEventBus bus = new InProcessEventBus();
        bus.subscribe(new MetricsEventListener(taskMetrics));
        bus.subscribe(new AuditEventListener(jdbc));
        return bus;
    }

    /** Redis EventBus — active when EVENT_BUS_TYPE=redis (default in docker). */
    @Bean
    @ConditionalOnProperty(name = "taskqueue.event-bus.type", havingValue = "redis")
    public EventBus redisEventBus(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            TaskQueueProperties props,
            RedisConnectionFactory connectionFactory,
            TaskMetrics taskMetrics,
            JdbcTemplate jdbc) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.afterPropertiesSet();
        container.start();

        String channel = props.eventBus() != null ? props.eventBus().channel() : "task-events";
        RedisEventBus bus = new RedisEventBus(redis, mapper, channel, container);
        bus.subscribe(new MetricsEventListener(taskMetrics));
        bus.subscribe(new AuditEventListener(jdbc));
        return bus;
    }

    // ── Outbox Relay (Phase 4) ────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public OutboxRelay outboxRelay(
            OutboxRepository outboxRepository,
            TaskQueue brokerQueue,
            EventBus eventBus,
            LeaderElector leaderElector,
            ObjectMapper mapper,
            TaskQueueProperties props) {
        int batchSize = props.outbox() != null ? props.outbox().batchSize() : 50;
        return new OutboxRelay(outboxRepository, brokerQueue, eventBus,
                leaderElector, mapper, batchSize);
    }

    // ── Leader-Elected Scheduler (Phase 4) ───────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "taskqueue.queue.type", havingValue = "redis-stream")
    public LeaderElectedTaskScheduler leaderElectedTaskScheduler(
            TaskRepository taskRepository,
            OutboxRepository outboxRepository,
            LeaderElector leaderElector,
            ObjectMapper mapper) {
        return new LeaderElectedTaskScheduler(
                taskRepository, outboxRepository, leaderElector, mapper);
    }
}
