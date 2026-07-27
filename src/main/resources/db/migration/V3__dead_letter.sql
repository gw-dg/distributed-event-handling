-- V3__dead_letter.sql
-- Creates the dead_letter table for persistently-failed tasks.
--
-- From dead-letter-queues.md:
--   "A dead letter queue is not an error log — it is a retryable backlog of
--    failures you chose to stop retrying automatically, kept so a human (or a
--    smarter automated policy) can decide what to do next."
--
-- Design decisions:
--   - Separate table (not a status on tasks) so the main tasks table stays lean
--     and operators can query / purge DLQ independently.
--   - task_id is NOT a FK so dead-lettered tasks can be deleted from tasks table
--     while preserving the DLQ record for audit.
--   - payload is duplicated here to ensure the full message is preserved even if
--     the tasks row is deleted.
--   - redrive_count tracks how many times an operator has re-queued this record.

CREATE TABLE dead_letter (
    id                 BIGSERIAL    PRIMARY KEY,
    task_id            VARCHAR(36)  NOT NULL,
    type               VARCHAR(255) NOT NULL,
    payload            JSONB        NOT NULL,
    reason             TEXT         NOT NULL,
    original_attempts  INT          NOT NULL DEFAULT 0,
    priority           INT          NOT NULL DEFAULT 0,
    failed_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    redrive_count      INT          NOT NULL DEFAULT 0,
    last_redrive_at    TIMESTAMPTZ
);

-- Index for operator queries: "show me all dead email tasks"
CREATE INDEX idx_dead_letter_type ON dead_letter (type);

-- Index for time-based queries: "show me what died in the last hour"
CREATE INDEX idx_dead_letter_failed_at ON dead_letter (failed_at DESC);
