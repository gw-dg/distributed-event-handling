-- V4__outbox.sql
-- Transactional outbox table (Phase 4 - Distributed System)
--
-- Purpose: Guarantee that a message is published to the broker if and only if
-- the database transaction that created it commits. The OutboxRelay polls this
-- table and forwards rows to Redis Streams, then marks them published.
--
-- Pattern: "Write to outbox inside the same transaction as your domain change,
-- then relay asynchronously." (distributed-transactions-and-event-sourcing.md)

CREATE TABLE IF NOT EXISTS outbox (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR(36) NOT NULL,                  -- task.id that triggered this event
    event_type   VARCHAR(64) NOT NULL,                  -- matches TaskEventType enum
    payload      TEXT        NOT NULL,                  -- JSON-serialised TaskEvent
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                            -- NULL = not yet relayed
);

-- Partial index: only unpublished rows are ever scanned by the relay.
-- The WHERE clause keeps the index tiny and fast even at high row counts.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox (created_at ASC)
    WHERE published_at IS NULL;

-- Index for fast lookup by aggregate_id (used by audit queries)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON outbox (aggregate_id);

COMMENT ON TABLE outbox IS
    'Transactional outbox: guarantees at-least-once delivery to the broker '
    'even if the broker is temporarily unavailable at commit time.';
