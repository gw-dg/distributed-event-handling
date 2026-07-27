package com.taskqueue.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j configuration for the task queue.
 *
 * <p>From circuit-breakers.md:
 *   "A circuit breaker is a state machine with three states:
 *    CLOSED (normal, requests flow), OPEN (tripped, requests fast-fail),
 *    HALF_OPEN (probe: let a few requests through to test recovery).
 *    The transition CLOSED → OPEN happens when the failure rate exceeds a threshold
 *    over a sliding window of calls."
 *
 * <h2>Settings explained</h2>
 * <ul>
 *   <li><b>slidingWindowSize=10</b> — evaluate failure rate over the last 10 calls.
 *       Small for local testing; production might use 50–100.
 *   <li><b>failureRateThreshold=50%</b> — open if ≥50% of the last 10 calls fail.
 *   <li><b>waitDurationInOpenState=10s</b> — wait 10s before probing (HALF_OPEN).
 *   <li><b>permittedCallsInHalfOpenState=3</b> — allow 3 trial calls in HALF_OPEN.
 *       If ≥50% succeed, close. Otherwise, re-open.
 *   <li><b>minimumNumberOfCalls=5</b> — need at least 5 calls before evaluating rate.
 *       Prevents a single failure from tripping the breaker immediately.
 * </ul>
 *
 * <p>We create one named breaker per handler type at runtime.
 * The registry is the factory — {@code registry.circuitBreaker("email")} returns
 * the existing breaker or creates one with this default config.
 */
@Configuration
public class ResilienceConfig {

    /**
     * Shared CircuitBreakerRegistry with default config applied to all task type breakers.
     *
     * <p>Individual handler types get their own breaker instance so a sick "email"
     * handler does not trip the "report" handler's breaker.
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                // Treat any exception as a failure; non-retryable results
                // are handled at the handler level, not here.
                .recordExceptions(Exception.class)
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Exposes the "taskqueue-default" breaker as a named bean for injection into tests
     * or actuator monitoring without requiring the registry.
     */
    @Bean
    public CircuitBreaker defaultCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("taskqueue-default");
    }
}
