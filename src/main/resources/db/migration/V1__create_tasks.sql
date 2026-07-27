-- V1__create_tasks.sql
-- Creates the core tasks table for the Phase 2 durable task queue.
--
-- Design decisions (from ch04 + task-queues.md):
--
-- 1. id is UUID stored as TEXT for simplicity with JdbcTemplate.
--    Use UUID type in Postgres production for storage efficiency if desired.
--
-- 2. payload is JSONB — binary JSON with indexing support.
--    Stored as text from Java, cast to JSONB on insert (::jsonb).
--
-- 3. status is TEXT (not an enum type) so we can add states without migrations.
--    The application's TaskStatus enum is the source of truth.
--
-- 4. version is an optimistic-locking counter for admin updates.
--    The leasing path uses SELECT ... FOR UPDATE SKIP LOCKED (no version needed).
--
-- 5. The partial index on (priority DESC, created_at ASC) WHERE status IN (...)
--    is the key index for the polling query. Postgres uses it only for PENDING,
--    RETRYING, SCHEDULED rows — a tiny fraction of total rows in steady state.
--
-- 6. A status + created_at index supports dashboard / monitoring queries.

CREATE TABLE IF NOT EXISTS tasks (
    id            TEXT        NOT NULL,
    type          TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    status        TEXT        NOT NULL,
    attempts      INT         NOT NULL DEFAULT 0,
    max_attempts  INT         NOT NULL DEFAULT 3,
    created_at    TIMESTAMPTZ NOT NULL,
    scheduled_at  TIMESTAMPTZ NOT NULL,
    priority      INT         NOT NULL DEFAULT 0,
    last_error    TEXT,
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT tasks_pkey PRIMARY KEY (id),
    CONSTRAINT tasks_attempts_check  CHECK (attempts  >= 0),
    CONSTRAINT tasks_priority_check  CHECK (priority  >= 0),
    CONSTRAINT tasks_max_attempts_check CHECK (max_attempts >= 1)
);

-- ── Indexes ──────────────────────────────────────────────────────────────────

-- Primary polling index: used by PostgresTaskQueue.pollDue()
-- Partial (WHERE status IN ...) means only runnable rows are indexed.
-- ORDER BY priority DESC, created_at ASC matches the query ORDER BY exactly
-- so Postgres can use an index scan without a sort.
CREATE INDEX IF NOT EXISTS idx_tasks_poll_due
    ON tasks (priority DESC, created_at ASC)
    WHERE status IN ('PENDING', 'RETRYING', 'SCHEDULED');

-- Status + time index for monitoring / admin queries (e.g., "show all FAILED today")
CREATE INDEX IF NOT EXISTS idx_tasks_status_created
    ON tasks (status, created_at DESC);

-- Type + status index for handler-specific dashboards
CREATE INDEX IF NOT EXISTS idx_tasks_type_status
    ON tasks (type, status);
