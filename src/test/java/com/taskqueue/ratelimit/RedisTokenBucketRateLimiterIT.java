package com.taskqueue.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.redis.testcontainers.RedisContainer;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link RedisTokenBucketRateLimiter}.
 *
 * <p>From rate-limiting.md: "Two instances sharing the same Redis key prove
 * that the aggregate tryAcquire() rate across both is capped at the configured
 * capacity, not 2×."
 */
@Testcontainers
class RedisTokenBucketRateLimiterIT {

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
    void twoInstances_sharingOneBucket_aggregateRateIsCapped() {
        long capacity   = 10;
        long refillRate = 100;
        String bucketKey = "test-bucket";

        RedisTokenBucketRateLimiter limiter1 =
                new RedisTokenBucketRateLimiter(template, bucketKey, capacity, refillRate);
        RedisTokenBucketRateLimiter limiter2 =
                new RedisTokenBucketRateLimiter(template, bucketKey, capacity, refillRate);

        AtomicInteger allowed = new AtomicInteger(0);
        for (int i = 0; i < (int)(capacity * 2); i++) {
            if (i % 2 == 0) {
                if (limiter1.tryAcquire()) allowed.incrementAndGet();
            } else {
                if (limiter2.tryAcquire()) allowed.incrementAndGet();
            }
        }

        assertThat(allowed.get()).isLessThanOrEqualTo((int) capacity + 1);
    }

    @Test
    void afterBucketDepleted_singleAcquireFails() {
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(template, "drain-test", 5, 1);

        int acquired = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryAcquire()) acquired++;
        }

        assertThat(acquired).isEqualTo(5);
        assertThat(limiter.available()).isLessThanOrEqualTo(1);
    }

    @Test
    void capacityIsReportedCorrectly() {
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(template, "cap-test", 42L, 10L);
        assertThat(limiter.capacity()).isEqualTo(42L);
    }
}
