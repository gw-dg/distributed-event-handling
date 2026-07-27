package com.taskqueue.retry;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExponentialBackoffRetryPolicy}.
 *
 * <p>From retries.md: "Test the backoff sequence, the cap, and jitter bounds.
 * Jitter means you can't assert exact values — assert that the value falls
 * within the expected range."
 */
class ExponentialBackoffRetryPolicyTest {

    // ── Deterministic (no jitter) tests ──────────────────────────────────

    @Test
    void delayDoublesEachAttemptWithoutJitter() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                10,
                false);

        assertThat(policy.nextDelay(1)).contains(Duration.ofSeconds(1));   // 2^0 = 1
        assertThat(policy.nextDelay(2)).contains(Duration.ofSeconds(2));   // 2^1 = 2
        assertThat(policy.nextDelay(3)).contains(Duration.ofSeconds(4));   // 2^2 = 4
        assertThat(policy.nextDelay(4)).contains(Duration.ofSeconds(8));   // 2^3 = 8
    }

    @Test
    void capIsRespectedAtMaxDelay() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                20,
                false);

        // 2^4 = 16s but cap is 10s
        assertThat(policy.nextDelay(5)).contains(Duration.ofSeconds(10));
        // All later attempts also capped
        assertThat(policy.nextDelay(10)).contains(Duration.ofSeconds(10));
    }

    @Test
    void returnsEmptyWhenMaxAttemptsExceeded() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                3,
                false);

        assertThat(policy.nextDelay(1)).isPresent();
        assertThat(policy.nextDelay(2)).isPresent();
        assertThat(policy.nextDelay(3)).isEmpty();  // attempt == maxAttempts → done
        assertThat(policy.nextDelay(4)).isEmpty();  // attempt > maxAttempts → done
    }

    // ── Jitter tests ────────────────────────────────────────────────────

    @Test
    void jitteredDelayFallsWithinRange() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                10,
                true);

        // Run many times to verify jitter bounds
        for (int i = 0; i < 1000; i++) {
            Optional<Duration> delay = policy.nextDelay(3);  // cap = min(4s, 60s) = 4s
            assertThat(delay).isPresent();
            assertThat(delay.get().toMillis())
                    .isBetween(0L, 4000L)  // full jitter: [0, capped]
                    .isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    void jitteredDelayNeverExceedsCap() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                20,
                true);

        for (int attempt = 1; attempt < 20; attempt++) {
            Optional<Duration> delay = policy.nextDelay(attempt);
            if (delay.isPresent()) {
                assertThat(delay.get().toMillis()).isLessThanOrEqualTo(5000L);
            }
        }
    }

    // ── Validation tests ────────────────────────────────────────────────

    @Test
    void rejectsNegativeBaseDelay() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(-1), Duration.ofSeconds(60), 3, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDelay");
    }

    @Test
    void rejectsMaxDelaySmallerThanBaseDelay() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(10), Duration.ofSeconds(5), 3, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay");
    }

    @Test
    void rejectsZeroMaxAttempts() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1), Duration.ofSeconds(60), 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void handlesLargeAttemptCountWithoutOverflow() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                1000,
                false);

        // attempt 63+ would overflow without the overflow guard in the implementation
        Optional<Duration> delay = policy.nextDelay(100);
        assertThat(delay).isPresent();
        assertThat(delay.get()).isEqualTo(Duration.ofMinutes(5));  // should be capped
    }
}
