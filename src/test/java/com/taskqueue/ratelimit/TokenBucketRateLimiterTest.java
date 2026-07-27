package com.taskqueue.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucketRateLimiter}.
 *
 * <p>These tests verify the core token bucket invariants:
 * <ol>
 *   <li>A full bucket allows {@code capacity} requests.
 *   <li>The bucket rejects requests when empty.
 *   <li>Tokens refill correctly after a delay.
 *   <li>Tokens cannot exceed capacity (no "sleep burst").
 * </ol>
 *
 * <p>No Spring context needed — TokenBucketRateLimiter is a pure Java class.
 */
class TokenBucketRateLimiterTest {

    @Test
    void fullBucketAllowsCapacityRequests() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1);

        // All 5 tokens should be immediately available
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire())
                    .as("Request %d should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void emptyBucketRejectsRequests() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 1);

        // Drain all tokens
        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();

        // Next request should be rejected
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void tokensRefillAfterWaiting() throws InterruptedException {
        // 2 tokens, 5 per second refill rate → 1 token every 200ms
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 5);

        // Drain
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();

        // Wait 250ms — enough for 1+ token to refill (200ms per token at 5/s)
        Thread.sleep(250);

        assertThat(limiter.tryAcquire())
                .as("Should be allowed after refill period")
                .isTrue();
    }

    @Test
    void tokensCannotExceedCapacity() throws InterruptedException {
        // Capacity=2, fast refill rate
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 100);

        // Wait long enough to theoretically accumulate many tokens
        Thread.sleep(200);

        // Should still only allow 2 (capacity cap)
        assertThat(limiter.tryAcquire()).isTrue();  // token 1
        assertThat(limiter.tryAcquire()).isTrue();  // token 2
        assertThat(limiter.tryAcquire()).isFalse(); // bucket was capped at 2
    }

    @Test
    void availableReturnsCurrentTokenCount() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(10, 1);

        assertThat(limiter.available()).isEqualTo(10);
        limiter.tryAcquire();
        assertThat(limiter.available()).isEqualTo(9);
        limiter.tryAcquire();
        assertThat(limiter.available()).isEqualTo(8);
    }

    @Test
    void capacityReturnsConfiguredMax() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(42, 10);
        assertThat(limiter.capacity()).isEqualTo(42);
    }
}
