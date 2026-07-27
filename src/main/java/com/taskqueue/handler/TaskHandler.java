package com.taskqueue.handler;

import com.taskqueue.common.Result;
import com.taskqueue.model.Task;

/**
 * Strategy contract for task execution.
 *
 * <p>Each concrete implementation handles one {@code type} of task.
 * In Phase 2, concrete handlers are Spring {@code @Component} beans and
 * are discovered automatically via {@code HandlerRegistry(List<TaskHandler>)}.
 *
 * <p>From strategy.md: the handler is the Strategy; the Worker is the Context.
 * The Worker delegates to whatever handler is registered — it never knows which
 * concrete implementation runs.
 *
 * <p>Implementations MUST be idempotent — from idempotency.md:
 * "At-least-once delivery means a task may run more than once. The defense is
 * idempotent handlers."
 */
@FunctionalInterface
public interface TaskHandler {

    /**
     * Executes the task and returns the outcome.
     *
     * <p>Return {@link Result#ok(Object)} on success.
     * Return {@link Result#retryable(String)} for transient failures (network timeout, etc.)
     * Return {@link Result#fail(String)} for permanent failures (bad payload, missing config).
     *
     * <p>Throwing an exception is treated the same as {@link Result#retryable(String)}.
     * Prefer returning explicit Results over exceptions for control flow.
     *
     * @param task the task to execute; never null
     * @return the execution outcome
     * @throws Exception on unexpected error (treated as retryable by the Worker)
     */
    Result<Void> handle(Task task) throws Exception;

    /**
     * The task type string this handler supports.
     *
     * <p>Used by {@link HandlerRegistry} to build the type→handler map.
     * In Phase 1 this was provided via {@link TaskRegistration}; in Phase 2
     * each handler declares its own type so Spring can auto-discover it.
     *
     * <p>Default implementation returns empty string so that lambda handlers
     * (registered via {@link TaskRegistration}) remain valid — the registry
     * prefers {@link TaskRegistration#taskType()} over this method for those.
     *
     * <p>Override in concrete {@code @Component} handler classes.
     *
     * @return task type string (e.g., "email", "report"); empty string for lambdas
     */
    default String supportedType() {
        return "";
    }
}
