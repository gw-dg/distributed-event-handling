package com.taskqueue.worker;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.taskqueue.handler.TaskHandler;
import com.taskqueue.model.Task;

/**
 * {@link TaskHandler} Decorator that enforces exactly-once execution per task ID.
 *
 * <p>From idempotency.md: "Before executing a handler, check the {@code processed_tasks}
 * table for the task's ID. On success, insert the result with {@code ON CONFLICT DO NOTHING}.
 * A concurrent or replayed delivery finds the row and returns the cached outcome without
 * running the side-effects again."
 *
 * <p>This decorator is automatically applied by {@link HandlerRegistry#register} in Phase 4,
 * so no handler can accidentally ship without idempotency protection.
 *
 * <p>From decorator.md: the outer contract ({@link TaskHandler}) is unchanged.
 * The delegate does the real work; this class does the idempotency bookkeeping.
 *
 * <p>Thread safety: {@link JdbcTemplate} is thread-safe; the DB constraint (PK on task_id)
 * is the serialisation point — even two concurrent threads executing the same task will
 * result in exactly one successful DB insert and exactly one handler invocation.
 */
public class IdempotentHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(IdempotentHandler.class);

    /** Sentinel detail returned when the task was already processed. */
    public static final String DUPLICATE_SUPPRESSED = "duplicate suppressed";

    private final TaskHandler delegate;
    private final JdbcTemplate jdbc;

    public IdempotentHandler(TaskHandler delegate, JdbcTemplate jdbc) {
        this.delegate = delegate;
        this.jdbc     = jdbc;
    }

    @Override
    public String supportedType() {
        return delegate.supportedType();
    }

    /**
     * Checks {@code processed_tasks} before delegating to the real handler.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>SELECT from processed_tasks WHERE task_id = ?</li>
     *   <li>If found → return the cached result (no side-effects).</li>
     *   <li>If not found → delegate to the real handler.</li>
     *   <li>On success → INSERT into processed_tasks (ON CONFLICT DO NOTHING).</li>
     * </ol>
     *
     * <p>The INSERT uses {@code ON CONFLICT DO NOTHING} so a race between two threads
     * executing the same task simultaneously is safe — only one will insert, the other
     * will no-op silently.
     */
    @Override
    public com.taskqueue.common.Result<Void> handle(Task task) throws Exception {
        // 1. Check idempotency store
        List<String> existing = jdbc.queryForList(
                "SELECT result FROM processed_tasks WHERE task_id = ?",
                String.class, task.id());

        if (!existing.isEmpty()) {
            log.info("[Idempotent] Task {} already processed (result={}), suppressing duplicate",
                    task.id(), existing.get(0));
            return com.taskqueue.common.Result.ok();   // return success without side-effects
        }

        // 2. Execute the real handler
        com.taskqueue.common.Result<Void> result;
        try {
            result = delegate.handle(task);
        } catch (Exception e) {
            // Don't record failures — only successes are idempotent
            throw new RuntimeException(e);
        }

        // 3. Record success (ON CONFLICT DO NOTHING handles concurrent duplicate)
        if (result instanceof com.taskqueue.common.Result.Success) {
            try {
                jdbc.update(
                        "INSERT INTO processed_tasks (task_id, result, handler_type, processed_at) "
                        + "VALUES (?, 'ok', ?, ?) ON CONFLICT DO NOTHING",
                        task.id(),
                        task.type(),
                        Timestamp.from(Instant.now()));
            } catch (DuplicateKeyException ignored) {
                // Concurrent execution — both completed, both inserted; one wins silently
            }
        }

        return result;
    }
}
