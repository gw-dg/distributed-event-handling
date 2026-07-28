package com.taskqueue.event;

import java.sql.Timestamp;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.taskqueue.domain.TaskEvent;
import com.taskqueue.port.TaskEventListener;

/**
 * Subscriber that writes every lifecycle event to the {@code task_audit_log} table.
 *
 * <p>From distributed-transactions-and-event-sourcing.md (event sourcing): "Store every
 * state-change event, not just the current state. The audit log lets you reconstruct
 * what happened, when, and why — essential for compliance, billing reconciliation,
 * and debugging replays."
 *
 * <p>Registered with the {@link com.taskqueue.port.EventBus} at startup. The bus
 * delivers events asynchronously after the transaction commits so this write is
 * NOT in the same transaction as the task state change.
 *
 * <p>Failure handling: exceptions are caught and logged. A failed audit write never
 * propagates back to the worker — the task result is not affected.
 */
public class AuditEventListener implements TaskEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    private final JdbcTemplate jdbc;

    public AuditEventListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onEvent(TaskEvent event) {
        try {
            jdbc.update(
                    "INSERT INTO task_audit_log "
                    + "(event_id, task_id, task_type, event_type, status, detail, occurred_at) "
                    + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?)",
                    event.eventId(),
                    event.taskId(),
                    event.taskType(),
                    event.eventType().name(),
                    event.status(),
                    event.detail(),
                    Timestamp.from(event.occurredAt() != null ? event.occurredAt() : Instant.now()));
        } catch (Exception e) {
            log.error("[Audit] Failed to write audit log for event {} task {}: {}",
                    event.eventId(), event.taskId(), e.getMessage());
        }
    }
}
