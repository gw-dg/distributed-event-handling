package com.taskqueue.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.taskqueue.common.Result;
import com.taskqueue.handler.TaskHandler;
import com.taskqueue.model.Task;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Decorator that wraps a {@link TaskHandler} invocation inside a Resilience4j
 * {@link CircuitBreaker}.
 *
 * <p>From circuit-breakers.md:
 *   "Wrap the call to the downstream service — not the whole worker loop.
 *    The circuit breaker decides whether to attempt the call, not whether to
 *    pull a task from the queue. Pulling is free; calling a sick downstream is costly."
 *
 * <p>Each task <em>type</em> gets its own breaker from the registry so that a sick
 * "email" downstream does not trip the "report" handler's breaker. This is the
 * key advantage of per-type breakers over a single global one.
 *
 * <h2>What happens when the breaker is OPEN</h2>
 * <ul>
 *   <li>Resilience4j throws {@link CallNotPermittedException}.
 *   <li>This decorator catches it and returns {@code Result.retryable("circuit breaker open")}.
 *   <li>The caller (Worker) routes this to RetryHandler, which schedules the task
 *       for a future attempt. When the task is re-polled, the breaker may have
 *       moved to HALF_OPEN and will accept the probe call.
 * </ul>
 *
 * <h2>Metrics integration</h2>
 * Resilience4j automatically exposes circuit breaker state and call metrics to
 * Micrometer if {@code io.github.resilience4j:resilience4j-micrometer} is on the
 * classpath (included transitively by resilience4j-spring-boot3).
 */
public class CircuitBreakerHandlerDecorator {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerHandlerDecorator.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerHandlerDecorator(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Executes the handler inside a per-type circuit breaker.
     *
     * @param handler the handler to invoke
     * @param task    the task being executed
     * @return the handler's result, or a retryable failure if the breaker is open
     * @throws Exception if the handler throws (breaker records this as a failure)
     */
    public Result<Void> execute(TaskHandler handler, Task task) throws Exception {
        // Each type gets its own breaker — lazily created on first call
        CircuitBreaker breaker = registry.circuitBreaker(task.type());

        try {
            return breaker.executeCheckedSupplier(() -> handler.handle(task));
        } catch (CallNotPermittedException open) {
            // Breaker is OPEN — fast-fail, do not call the downstream
            log.warn("Circuit breaker for type '{}' is OPEN — fast-failing task {}",
                    task.type(), task.id());
            return Result.retryable("Circuit breaker OPEN for type: " + task.type());
        } catch (Exception ex) {
            // Handler threw — propagate so Worker can route to RetryHandler
            throw ex;
        } catch (Throwable t) {
            // executeCheckedSupplier declares throws Throwable; wrap Errors so
            // our method signature (throws Exception) stays valid.
            throw new RuntimeException("Unexpected throwable during handler execution", t);
        }
    }
}
