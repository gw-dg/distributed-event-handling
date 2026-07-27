package com.taskqueue.dlq;

import com.taskqueue.model.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Dead Letter Queue implementation — logs dead tasks to stdout.
 *
 * <p>This is a placeholder that makes the DLQ port fully functional in Phase 2
 * without needing a Postgres dead_letter table (that's a Phase 3 addition
 * via V3 migration + PostgresDeadLetterQueue).
 *
 * <p>In production, operators monitoring application logs will see all dead
 * task details: id, type, attempts, payload, and failure reason. This is
 * sufficient for Phase 2 demo/testing purposes.
 *
 * <p>Phase 3 adds a persistent DLQ so operators can query, replay, and inspect
 * dead tasks via an admin endpoint without grep-ing logs.
 */
@Component
public final class LoggingDeadLetterQueue implements DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(LoggingDeadLetterQueue.class);

    @Override
    public void send(Task task, String reason) {
        // Never throws — exceptions inside DLQ must not propagate to workers
        try {
            log.error(
                    "[DLQ] DEAD TASK  id={} type={} attempts={}/{} reason='{}' payload={}",
                    task.id(),
                    task.type(),
                    task.attempts(),
                    task.maxAttempts(),
                    reason,
                    task.payload());
        } catch (Exception ignored) {
            // Last-resort safety: even logging must not kill the worker
        }
    }
}
