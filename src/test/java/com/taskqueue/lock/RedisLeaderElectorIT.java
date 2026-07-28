package com.taskqueue.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.redis.testcontainers.RedisContainer;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for {@link RedisLeaderElector}.
 *
 * <p>From leader-election.md + consensus-and-failure-detection.md:
 * "At any point in time, at most one node holds leadership. If the leader's
 * heartbeat stops, its TTL expires and another node acquires leadership."
 */
@Testcontainers
class RedisLeaderElectorIT {

    @Container
    static final RedisContainer redis = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7.2.4"));

    private StringRedisTemplate template;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getFirstMappedPort());
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().flushAll();
    }

    @AfterEach
    void tearDown() {
        template.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void exactlyOneOfTwoNodesBecomesLeader() throws Exception {
        long ttlMs = 2000;
        long heartbeatMs = 500;

        RedisLeaderElector node1 = new RedisLeaderElector(template, "node-1", ttlMs, heartbeatMs);
        RedisLeaderElector node2 = new RedisLeaderElector(template, "node-2", ttlMs, heartbeatMs);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Boolean> f1 = pool.submit(() -> { go.await(); return node1.tryAcquire("test-role"); });
        Future<Boolean> f2 = pool.submit(() -> { go.await(); return node2.tryAcquire("test-role"); });

        go.countDown();
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.SECONDS);

        boolean n1 = f1.get();
        boolean n2 = f2.get();

        assertThat(n1 ^ n2).isTrue();
        assertThat(node1.isLeader("test-role")).isEqualTo(n1);
        assertThat(node2.isLeader("test-role")).isEqualTo(n2);

        node1.close();
        node2.close();
    }

    @Test
    void whenLeaderTtlExpires_otherNodeAcquires() throws Exception {
        long ttlMs = 500;
        long heartbeatMs = 10000;

        RedisLeaderElector leader  = new RedisLeaderElector(template, "leader-node", ttlMs, heartbeatMs);
        RedisLeaderElector standby = new RedisLeaderElector(template, "standby-node", ttlMs, heartbeatMs);

        boolean acquired = leader.tryAcquire("failover-role");
        assertThat(acquired).isTrue();
        assertThat(leader.isLeader("failover-role")).isTrue();

        Thread.sleep(ttlMs + 200);

        boolean failover = standby.tryAcquire("failover-role");
        assertThat(failover).isTrue();
        assertThat(standby.isLeader("failover-role")).isTrue();
        assertThat(leader.isLeader("failover-role")).isFalse();

        leader.close();
        standby.close();
    }

    @Test
    void release_shouldAllowOtherNodeToAcquire() {
        RedisLeaderElector node1 = new RedisLeaderElector(template, "node-A", 5000, 1000);
        RedisLeaderElector node2 = new RedisLeaderElector(template, "node-B", 5000, 1000);

        assertThat(node1.tryAcquire("release-role")).isTrue();
        assertThat(node2.tryAcquire("release-role")).isFalse();

        node1.release("release-role");

        assertThat(node2.tryAcquire("release-role")).isTrue();

        node1.close();
        node2.close();
    }
}
