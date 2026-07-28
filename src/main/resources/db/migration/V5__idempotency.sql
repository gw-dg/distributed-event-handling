-- V5__idempotency.sql
-- Processed-tasks dedup table (Phase 4 - Distributed System)
--
-- Purpose: Gives worker handlers exactly-once semantics even when Redis Streams
-- delivers the same task twice (e.g., after a worker crash before ACK, or
-- during leader failover). Before executing a handler, the IdempotentHandler
-- checks this table. On success, it inserts the task_id. ON CONFLICT DO NOTHING
-- means a duplicate quietly no-ops.
--
-- From idempotency.md: "Record the result of a successful operation atomically.
-- A concurrent or replayed message finds the row and returns the cached outcome
-- without running the handler side-effects again."

CREATE TABLE IF NOT EXISTS processed_tasks (
    task_id      VARCHAR(36)  PRIMARY KEY,              -- task.id (UUID string)
    result       TEXT         NOT NULL,                 -- "ok" | error message
    handler_type VARCHAR(64)  NOT NULL,                 -- task.type at execution time
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE processed_tasks IS
    'Idempotency log: prevents a handler from executing more than once for the '
    'same task_id even if the broker delivers the message multiple times.';

-- Optional: TTL-style cleanup index.
-- Run "DELETE FROM processed_tasks WHERE processed_at < now() - interval ''30 days''"
-- on a schedule to keep the table bounded.
CREATE INDEX IF NOT EXISTS idx_processed_tasks_at
    ON processed_tasks (processed_at DESC);

-- Also create task_audit_log used by AuditEventListener
CREATE TABLE IF NOT EXISTS task_audit_log (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     UUID         NOT NULL DEFAULT gen_random_uuid(),
    task_id      VARCHAR(36)  NOT NULL,
    task_type    VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,                 -- matches TaskEventType enum
    status       VARCHAR(32),
    detail       TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_audit_task_id
    ON task_audit_log (task_id, occurred_at DESC);

COMMENT ON TABLE task_audit_log IS
    'Full audit trail of every lifecycle event for every task. '
    'Used for compliance, billing reconciliation, and replay debugging.';
