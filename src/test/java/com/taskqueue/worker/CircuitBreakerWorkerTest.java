package com.taskqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.taskqueue.common.Result;
import com.taskqueue.handler.TaskHandler;
import com.taskqueue.model.Task;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Unit tests for {@link CircuitBreakerHandlerDecorator}.
 *
 * <p>From circuit-breakers.md:
 *   "Test the state machine: CLOSED allows calls, OPEN fast-fails, HALF_OPEN probes."
 *
 * <p>We use a real Resilience4j registry (no mocking) to verify actual state
 * transitions. The handler is mocked to control success/failure outcomes.
 */
class CircuitBreakerWorkerTest {

    private CircuitBreakerRegistry registry;
    private CircuitBreakerHandlerDecorator decorator;

    @BeforeEach
    void setup() {
        // Tight config for fast testing
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(75.0f)    // open at 75% (3 of 4 fail)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();

        registry  = CircuitBreakerRegistry.of(config);
        decorator = new CircuitBreakerHandlerDecorator(registry);
    }

    private Task task(String id, String type) {
        return Task.create(id, type, "{\"k\":\"v\"}", 5, 0);
    }

    @Test
    void closedStateAllowsCallsThrough() throws Exception {
        TaskHandler handler = mock(TaskHandler.class);
        when(handler.handle(any())).thenReturn(Result.ok(null));

        Task task = task("cb-001", "email");
        Result<Void> result = decorator.execute(handler, task);

        assertThat(result.isSuccess()).isTrue();
        verify(handler, times(1)).handle(task);
    }

    @Test
    void openStateReturnsFastFailWithoutCallingHandler() throws Exception {
        TaskHandler handler = mock(TaskHandler.class);
        // Fail enough calls to trip the breaker (3 of 4 = 75%)
        when(handler.handle(any())).thenThrow(new RuntimeException("downstream down"));

        Task task = task("cb-002", "payment");

        // Execute 4 calls — all fail → should trip breaker
        for (int i = 0; i < 4; i++) {
            try {
                decorator.execute(handler, task);
            } catch (Exception ignored) {
                // exceptions during CLOSED state are expected
            }
        }

        CircuitBreaker breaker = registry.circuitBreaker("payment");
        assertThat(breaker.getState())
                .as("Breaker should be OPEN after 75%+ failures")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Next call should fast-fail without calling handler
        Result<Void> fastFail = decorator.execute(handler, task);

        assertThat(fastFail.isSuccess()).isFalse();
        assertThat(fastFail.isRetryable()).isTrue();
        assertThat(fastFail.errorMessage()).contains("OPEN");
        // Handler was called 4 times during CLOSED, 0 times during OPEN
        verify(handler, times(4)).handle(any());
    }

    @Test
    void differentTypesHaveSeparateBreakers() throws Exception {
        TaskHandler emailHandler  = mock(TaskHandler.class);
        TaskHandler reportHandler = mock(TaskHandler.class);

        when(emailHandler.handle(any())).thenThrow(new RuntimeException("email server down"));
        when(reportHandler.handle(any())).thenReturn(Result.ok(null));

        Task emailTask  = task("cb-003", "email");
        Task reportTask = task("cb-004", "report");

        // Trip the email breaker
        for (int i = 0; i < 4; i++) {
            try { decorator.execute(emailHandler, emailTask); } catch (Exception ignored) {}
        }

        CircuitBreaker emailBreaker  = registry.circuitBreaker("email");
        CircuitBreaker reportBreaker = registry.circuitBreaker("report");

        // email breaker should be OPEN
        assertThat(emailBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // report breaker should still be CLOSED (only 0-1 calls)
        assertThat(reportBreaker.getState())
                .as("Report breaker should be unaffected by email failures")
                .isNotEqualTo(CircuitBreaker.State.OPEN);

        // Report calls still work
        Result<Void> reportResult = decorator.execute(reportHandler, reportTask);
        assertThat(reportResult.isSuccess()).isTrue();
    }

    @Test
    void successfulCallInClosedStateIsRecorded() throws Exception {
        TaskHandler handler = mock(TaskHandler.class);
        when(handler.handle(any())).thenReturn(Result.ok(null));

        Task task = task("cb-005", "sms");
        Result<Void> result = decorator.execute(handler, task);

        assertThat(result.isSuccess()).isTrue();
        CircuitBreaker breaker = registry.circuitBreaker("sms");
        assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }
}
