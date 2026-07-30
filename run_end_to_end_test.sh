#!/usr/bin/env bash

echo "============================================================"
echo " Step 1: Shutting down current Docker container stack..."
echo "============================================================"
docker-compose down

echo "============================================================"
echo " Step 2: Rebuilding container images without cache..."
echo "============================================================"
docker-compose build --no-cache

echo "============================================================"
echo " Step 3: Starting fresh cluster (API + 2 Workers + Postgres + Redis)..."
echo "============================================================"
docker-compose up -d --scale worker=2

echo "Waiting 60 seconds (1 minute) for complete container initialization..."
sleep 60

echo "============================================================"
echo " Step 4: Submitting 50 Diverse Workload Tasks..."
echo "============================================================"

# 1. 25 Immediate Tasks
for i in {1..25}; do
  TYPE=$([ $((i % 2)) -eq 0 ] && echo "email" || echo "report")
  curl -s -X POST http://localhost:8080/tasks \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"$TYPE\",\"payload\":{\"to\":\"user$i@example.com\"},\"maxAttempts\":3}" > /dev/null
  sleep 0.1
done

# 2. 10 Delayed Tasks (scheduled 30s in future)
FUTURE_TIME=$(date -u -d "+30 seconds" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || date -u -v+30s +"%Y-%m-%dT%H:%M:%SZ")
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/tasks \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"email\",\"payload\":{\"to\":\"delayed$i@example.com\"},\"scheduledAt\":\"$FUTURE_TIME\",\"maxAttempts\":3}" > /dev/null
  sleep 0.1
done

# 3. 10 Poison / DLQ Tasks
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/tasks \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"unregistered_type\",\"payload\":{\"bad\":\"data_$i\"},\"maxAttempts\":1}" > /dev/null
  sleep 0.1
done

# 4. 5 High Priority Tasks
for i in {1..5}; do
  curl -s -X POST http://localhost:8080/tasks \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"email\",\"payload\":{\"to\":\"vip$i@example.com\"},\"priority\":100}" > /dev/null
  sleep 0.1
done

echo "All 50 tasks submitted successfully!"

echo "============================================================"
echo " Step 5: Checking PostgreSQL Accumulated Task Counts..."
echo "============================================================"
docker exec taskqueue-postgres psql -U taskqueue -d taskqueue -c "SELECT status, COUNT(*) FROM tasks GROUP BY status ORDER BY status;"

echo "============================================================"
echo " Step 6: Fetching Live Prometheus Metrics Summary..."
echo "============================================================"
sleep 3
curl -s http://localhost:8080/actuator/prometheus | grep -E "taskqueue_tasks_submitted_total|taskqueue_queue_depth"

echo "============================================================"
echo " Verification Complete! Dashboards:"
echo " • Grafana Dashboard:   http://localhost:3000/dashboards"
echo " • Prometheus Explorer:  http://localhost:9090"
echo "============================================================"
