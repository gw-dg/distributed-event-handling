# Operations Runbook — Distributed Task Queue

## Quick Reference

| Signal | Threshold | Action |
|--------|-----------|--------|
| DLQ depth | > 0 | Investigate poison messages (see §1) |
| Consumer group lag | > 500 tasks | Scale workers (see §2) |
| Leader not elected | > 30s | Check Redis connectivity (see §3) |
| Circuit breaker OPEN | Any type | Check downstream (see §4) |
| Error rate | > 5% | Check logs + circuit breaker state |

---

## §1 — DLQ Depth Spikes

**What it means**: Tasks exhausted all retry attempts without succeeding.

**Immediate actions**:
```bash
# Inspect the last 10 dead tasks
redis-cli XREAD COUNT 10 STREAMS dlq-stream 0-0

# Or via the REST API:
curl localhost:8080/dlq?limit=20

# Check the task's audit history
curl localhost:8080/tasks/{task_id}
```

**Common causes and fixes**:

| Cause | Fix |
|-------|-----|
| No handler registered for task type | Register a handler in `HandlerRegistry`; resubmit task |
| External service down (email SMTP) | Fix the service; replay tasks from DLQ |
| Poison message (bad payload) | Fix the data; discard the DLQ entry |
| Handler bug (throws NullPointerException) | Fix the code; redeploy; replay |

**Replay a DLQ task**:
```bash
# 1. Get the payload from the DLQ stream entry
PAYLOAD=$(redis-cli XRANGE dlq-stream - + COUNT 1 | grep payload | awk '{print $2}')

# 2. Resubmit via the API
curl -X POST localhost:8080/tasks \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD"
```

---

## §2 — Consumer Group Lag

**What it means**: The `task-stream` has more entries than workers are consuming.

**Check lag**:
```bash
redis-cli XINFO GROUPS task-stream
# Look at: pel-count (in-flight), last-delivered-id vs stream length
```

**Actions**:
```bash
# Scale workers immediately
docker compose up -d --scale worker=8

# Monitor recovery
watch -n2 'redis-cli XINFO GROUPS task-stream | grep pel-count'
```

**If PEL is large but workers are consuming**: dead workers left entries in the PEL.
Force reclaim:
```bash
# reclaimStale() runs every 5s automatically in WorkerPool.
# To force immediate reclaim, restart all workers:
docker compose restart worker
```

---

## §3 — Leader Not Electing

**What it means**: No node holds the `leader:outbox-relay` or `leader:scheduler` key.
The outbox relay and scheduler are not running.

**Check**:
```bash
redis-cli GET leader:outbox-relay
redis-cli GET leader:scheduler
redis-cli TTL leader:outbox-relay
```

**If key is empty/expired**:
1. Check Redis connectivity from worker containers: `docker exec <worker> redis-cli -h redis ping`
2. Check worker logs: `docker compose logs worker | grep "Leader"`
3. Restart one worker — it will acquire leadership within one heartbeat interval (3s).

**If Redis is completely down**:
- Postgres receives submissions (outbox rows accumulate)
- Relay pauses — rows will be relayed when Redis recovers
- Workers idle (cannot poll from Redis Streams)
- API returns 500 if Redis is required for enqueue

Fix: Restore Redis. Relay will drain the backlog automatically.

---

## §4 — Circuit Breaker Open

**What it means**: A handler's downstream is failing at > 50% (configurable) rate.
The breaker is protecting other tasks from waiting for a slow/dead dependency.

**Check**:
```bash
# Resilience4j metrics via Actuator
curl localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | python -m json.tool

# Prometheus query
curl -s localhost:9090/api/v1/query?query=resilience4j_circuitbreaker_state | jq .
```

**States and actions**:

| State | Meaning | Action |
|-------|---------|--------|
| CLOSED | Normal | None |
| OPEN | Downstream failing; tasks fast-failing | Fix the downstream service |
| HALF_OPEN | Probing after wait duration | Monitor — will close if probe succeeds |

**To force breaker to HALF_OPEN** (reset early):
```bash
# Wait for wait-duration-open (default: 10s) OR restart the worker
docker compose restart worker
```

**Breaker opened for "email" type**:
- Email SMTP server is down or rate-limiting you
- All "email" tasks will fast-fail as retryable → retry budget → DLQ
- Fix: Restore SMTP service; tasks in RETRYING state will be retried

---

## §5 — High Error Rate (> 5%)

**Investigation sequence**:
```bash
# 1. Check application logs
docker compose logs --tail=100 worker api | grep "ERROR\|WARN"

# 2. Check task failure breakdown by type
curl localhost:8080/actuator/prometheus | grep taskqueue_tasks_failed

# 3. Check DLQ size
curl localhost:8080/dlq?limit=5

# 4. Check database
docker exec taskqueue-postgres psql -U taskqueue -c \
  "SELECT status, COUNT(*) FROM tasks GROUP BY status ORDER BY count DESC"
```

---

## §6 — Graceful Shutdown Procedure

```bash
# 1. Stop accepting new submissions (optional: nginx/LB rule)

# 2. Graceful shutdown — waits up to 30s for in-flight tasks
docker compose stop worker   # sends SIGTERM; Spring Boot drains

# 3. Verify all PEL entries are ACKed
redis-cli XINFO GROUPS task-stream | grep pel-count
# Should be 0 after worker drain completes

# 4. Stop remaining services
docker compose stop api prometheus grafana

# 5. Stop data stores last
docker compose stop redis postgres
```
