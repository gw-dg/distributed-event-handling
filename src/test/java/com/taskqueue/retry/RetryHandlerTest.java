package com.taskqueue.retry;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.taskqueue.common.Result;
import com.taskqueue.dlq.DeadLetterQueue;
import com.taskqueue.model.Task;
import com.taskqueue.repo.TaskRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetryHandler}.
 *
 * <p>No Spring context needed — all dependencies are Mockito mocks.
 * This is the correct approach for testing pure logic: fast, focused, isolated.
 *
 * <p>From ch05: "Unit-test services without Spring."
 * From strategy.md: tests verify that the Strategy (RetryPolicy) is consulted
 * correctly and its decision is honored.
 */
class RetryHandlerTest {

    private RetryPolicy retryPolicy;
    private TaskRepository repository;
    private DeadLetterQueue deadLetterQueue;
    private RetryHandler handler;

    @BeforeEach
    void setUp() {
        retryPolicy     = Mockito.mock(RetryPolicy.class);
        repository      = Mockito.mock(TaskRepository.class);
        deadLetterQueue = Mockito.mock(DeadLetterQueue.class);
        handler         = new RetryHandler(retryPolicy, repository, deadLetterQueue);
    }

    @Test
    void retryableFailureWithBudgetSchedulesRetrying() {
        Task task = runningTask("t-1");
        when(retryPolicy.nextDelay(1)).thenReturn(Optional.of(Duration.ofSeconds(5)));

        handler.handleFailure(task, Result.retryable("timeout"));

        // Should save with RETRYING status
        verify(repository).save(any(Task.class));
        // DLQ must NOT be called
        verify(deadLetterQueue, never()).send(any(), anyString());
    }

    @Test
    void retryableFailureWithExhaustedBudgetSendsToDlq() {
        Task task = runningTask("t-2");
        // Policy says no more retries
        when(retryPolicy.nextDelay(1)).thenReturn(Optional.empty());

        handler.handleFailure(task, Result.retryable("timeout after exhaustion"));

        // DLQ must be called
        verify(deadLetterQueue).send(any(Task.class), anyString());
        // Repository saves the DEAD task
        verify(repository).save(any(Task.class));
    }

    @Test
    void nonRetryableFailureGoesDirectlyToDlq() {
        Task task = runningTask("t-3");

        handler.handleFailure(task, Result.fail("permanent error - bad payload"));

        // DLQ called immediately — no policy check needed
        verify(deadLetterQueue).send(any(Task.class), anyString());
        verify(retryPolicy, never()).nextDelay(any(Integer.class));
    }

    @Test
    void exceptionIsHandledAsRetryable() {
        Task task = runningTask("t-4");
        when(retryPolicy.nextDelay(1)).thenReturn(Optional.of(Duration.ofSeconds(10)));

        handler.handleException(task, new RuntimeException("connection reset"));

        // Treated as retryable — should schedule retry
        verify(repository).save(any(Task.class));
        verify(deadLetterQueue, never()).send(any(), anyString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Task runningTask(String id) {
        // Create a task that is in RUNNING state (1 attempt consumed)
        Task pending = Task.create(id, "email", "{}", 5, 0);
        return pending.markRunning();
    }
}
