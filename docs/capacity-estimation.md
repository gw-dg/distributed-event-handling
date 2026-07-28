# Capacity Estimation

Back-of-envelope calculations for the Distributed Task Queue at production scale.

## Assumptions

| Metric | Value | Notes |
|--------|-------|-------|
| Task submit rate | 1,000 tasks/sec | Peak throughput |
| Average payload size | 1 KB | JSON task payload |
| Task retention | 30 days | In Postgres before archival |
| Handler p99 latency | 200 ms | Slowest handler (email SMTP) |
| Worker poll interval | 300 ms | XREADGROUP block timeout |

## Storage

### PostgreSQL — tasks table

```
Tasks per day:         1,000 × 86,400 = 86.4 M rows/day
Row size (estimate):   ~500 bytes (all fields including indexes)
Daily storage:         86.4 M × 500 B ≈ 43 GB/day
30-day retention:      43 GB × 30 ≈ 1.3 TB

→ Recommendation: Archive completed tasks older than 30 days to S3/Parquet.
  Partition the tasks table by created_at month.
```

### PostgreSQL — outbox table

```
Outbox rows are transient: deleted once published.
Relay runs every 250ms, batch size 50 → max backlog at 1,000 tasks/s:
  Relay throughput: 50 rows / 250 ms = 200 rows/s (1 relay node)
  At 1,000 tasks/s submit rate: backlog grows if relay is single-threaded.
  
→ Scale relay: use a pool of leader-elected relay workers, each taking
  a disjoint batch via FOR UPDATE SKIP LOCKED.
  5 relay workers × 200 rows/s = 1,000 rows/s — matches submit rate.
```

### Redis — in-flight tasks (PEL)

```
Visibility timeout: 30 seconds
At 1,000 tasks/s, max PEL depth per worker:
  In-flight per worker = p99_handler_latency / poll_interval = 200ms / 300ms ≈ 1 task
  4 workers per node = 4 in-flight tasks per node
  3 nodes = 12 in-flight tasks

Each Redis Streams entry: ~200 bytes (ID + fields)
12 × 200 B = 2.4 KB — negligible.

Total stream size (unACKed): at 1,000 tasks/s and 200ms handler latency:
  In-flight at any moment = 1,000 × 0.2 = 200 entries
  200 × 200 B ≈ 40 KB

→ Redis memory for the task stream: < 1 MB at this throughput.
  Redis 256 MB limit (in docker-compose.yml) is generous.
```

## Compute — Worker Count Formula

```
Required workers = ceil(target_throughput × p99_handler_latency)
                 = ceil(1,000 tasks/s × 0.2 s)
                 = ceil(200)
                 = 200 concurrent workers

With virtual threads (Java 21 Loom): one thread per in-flight task,
no thread pool sizing needed. The bottleneck becomes I/O (Redis, Postgres, SMTP).

→ Start with 3 worker containers × 4 virtual threads each = 12 workers.
  Scale horizontally: docker compose up --scale worker=N.
  Monitor: task-stream PEL depth. If growing → add workers.
```

## Kafka Partition Sizing (if switched to Kafka)

```
Target: 10 M tasks/day = ~116 tasks/sec
Max consumer lag budget: 60 seconds
Required consumer throughput: 116 tasks/s × (1 + lag_factor)

Partition count: max(#consumers, throughput / partition_throughput)
Kafka partition throughput: ~10 MB/s write, ~50 MB/s read
Task payload: 1 KB → 10,000 tasks/s per partition

For 10 M tasks/day (116 tasks/s): 1 partition is sufficient.
For burst headroom (10× = 1,160 tasks/s): 2 partitions minimum.

→ Recommendation: 4 partitions (per task type if using type-keyed partitioning).
  Gives 4× parallelism with per-type ordering preserved.
```

## Scaling Decision Tree

```
Queue depth rising?     → Add worker containers (--scale worker=N)
Handler latency rising? → Investigate handler bottleneck (SMTP timeout? DB slow query?)
Postgres writes slow?   → Add read replicas for pollDue(), index on (status, scheduled_at)
Redis memory growing?   → Trim old stream entries: XTRIM task-stream MAXLEN ~10000
DLQ depth rising?       → Investigate handler bugs; increase retry budget
```
