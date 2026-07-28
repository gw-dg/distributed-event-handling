# Distributed Task Queue — Architecture

## Overview

A four-phase evolution from a pure-Java in-memory queue to a horizontally-scalable,
distributed task processing system.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Phase 4 Architecture                           │
│                                                                         │
│  REST API Layer         Broker Layer          Worker Layer              │
│  ┌──────────┐           ┌──────────────┐      ┌──────────────────────┐ │
│  │ POST     │──outbox──▶│ Redis Streams│◀─────│ Worker (virtual thr) │ │
│  │ /tasks   │           │ task-stream  │      │ AckableTaskQueue     │ │
│  └──────────┘           └──────────────┘      │ IdempotentHandler    │ │
│                         ┌──────────────┐      └──────────────────────┘ │
│  Persistence Layer      │ dlq-stream   │                               │
│  ┌──────────┐           └──────────────┘      Event Bus Layer          │
│  │ Postgres │           ┌──────────────┐      ┌──────────────────────┐ │
│  │ tasks    │           │ Redis pub/sub│◀─────│ RedisEventBus        │ │
│  │ outbox   │           │ task-events  │─────▶│ MetricsEventListener │ │
│  │ dead_ltr │           └──────────────┘      │ AuditEventListener   │ │
│  │ audit_lg │                                 └──────────────────────┘ │
│  │ proc_tsk │           Leader Election                                 │
│  └──────────┘           ┌──────────────┐                               │
│                         │ Redis keys   │                               │
│  Outbox Relay           │ leader:*     │                               │
│  ┌──────────┐           └──────────────┘                               │
│  │ OutboxRly│                                                           │
│  │ (leader) │                                                           │
│  └──────────┘                                                           │
└─────────────────────────────────────────────────────────────────────────┘
```

## Phase Evolution

| Phase | Description | Key Tech |
|-------|-------------|----------|
| 1 | Pure Java engine | `LinkedBlockingQueue`, `ExecutorService` |
| 2 | Durable + HTTP | Spring Boot, PostgreSQL, Flyway, REST API |
| 3 | Guardrails + Observability | Resilience4j, Micrometer, Prometheus |
| 4 | Horizontal + Event-driven | Redis Streams, Outbox, Leader Election, EventBus |

## Data Flow (Phase 4)

1. **Submit**: `POST /tasks` → `TaskService.submit()` opens transaction → inserts task row + outbox row → commits → returns 201.
2. **Relay**: `OutboxRelay` (leader-elected, every 250ms) → polls unpublished outbox rows with `FOR UPDATE SKIP LOCKED` → calls `queue.enqueue()` → marks published → all in one transaction.
3. **Poll**: Worker calls `AckableTaskQueue.poll(timeout)` → Redis `XREADGROUP` → entry moves to PEL.
4. **Execute**: Worker looks up task from Postgres → `IdempotentHandler` checks `processed_tasks` → delegates to real handler.
5. **ACK/NACK**: On success → `XACK` (removes from PEL) + insert into `processed_tasks`. On failure → leave in PEL; `reclaimStale()` will re-deliver after visibility timeout.
6. **Events**: Worker publishes `TaskEvent` to `RedisEventBus` → `MetricsEventListener` updates Micrometer counters → `AuditEventListener` writes to `task_audit_log`.

## CAP Analysis

| Component | Consistency | Availability | Partition Tolerance |
|-----------|-------------|--------------|---------------------|
| PostgreSQL (tasks, outbox) | CP | Degraded during partition | ✓ |
| Redis Streams (broker) | AP | ✓ | ✓ (may lose uncommitted data) |
| Redis pub/sub (EventBus) | AP | ✓ | Fire-and-forget |
| Redis leader election | CP | Degraded if Redis down | ✓ |

**Design choice**: The task pipeline (Postgres → outbox → Redis Streams) is CP for task durability.
The metrics/audit path (Redis pub/sub) is AP — acceptable for observability (best-effort).

## Failure Modes

| Failure | Impact | Recovery |
|---------|--------|----------|
| Worker crashes mid-task | Task stays in PEL | `reclaimStale()` re-delivers within visibility timeout |
| Leader dies | Outbox relay pauses | Other node acquires within `ttlMs` (10s default) |
| Redis down | New enqueues fail; workers idle | Postgres keeps tasks; relay resumes when Redis recovers |
| Postgres down | Submissions fail (5xx) | Existing Redis entries continue processing |
| Duplicate delivery | Handler runs twice | `IdempotentHandler` suppresses via `processed_tasks` PK |
