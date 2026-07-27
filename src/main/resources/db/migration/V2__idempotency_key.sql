-- V2__idempotency_key.sql
-- Adds idempotency key support so duplicate POST /tasks submissions with the same
-- key are deduplicated at the database layer.
--
-- From idempotency.md:
--   "The natural key is the Task.id (a UUID assigned once at submission and stable
--    across every redelivery). Sometimes you want a business key instead — e.g.
--    'charge:orderId' so that two different tasks for the same order also dedupe."
--
-- Design:
--   - idempotency_key is nullable: clients that don't supply one get no dedup guarantee.
--   - The unique partial index only applies WHERE idempotency_key IS NOT NULL,
--     so NULL keys don't conflict with each other.
--   - Application layer checks by idempotency_key on submit; DB is the safety net.
--
-- Usage:
--   Client sends: POST /tasks  with header or body field idempotencyKey: "charge:order-42"
--   First call  -> creates task, returns 202 with task id
--   Retry call  -> DB UNIQUE constraint catches duplicate, service returns existing task id

ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS idempotency_key TEXT;

-- Unique partial index: enforces one task per idempotency key, only for non-null keys.
-- INSERT ... ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
-- is the dedup pattern used in JdbcTaskRepository.saveWithIdempotencyKey().
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_idempotency_key
    ON tasks (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
