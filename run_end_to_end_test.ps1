# ── Phase 4 Task Queue Automated Cluster Runner & Tester ─────────────────────
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 1: Shutting down stack & cleaning Docker build cache..." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker-compose down
docker builder prune -a -f | Out-Null

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 2: Rebuilding fresh container images without cache..." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker-compose build --no-cache

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 3: Starting fresh cluster (API + 2 Workers + Postgres + Redis)..." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker-compose up -d --scale worker=2

Write-Host "Waiting 60 seconds (1 minute) for complete container initialization & Flyway migrations..." -ForegroundColor Gray
Start-Sleep -Seconds 60

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 4: Truncating Database & Clearing Redis Streams for Pristine Baseline..." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker exec taskqueue-postgres psql -U taskqueue -d taskqueue -c "TRUNCATE TABLE tasks, outbox RESTART IDENTITY;" | Out-Null
docker exec taskqueue-redis redis-cli DEL task-stream dlq-stream | Out-Null
Write-Host "Database & Redis Streams reset to 0 baseline!" -ForegroundColor Green

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 5: Submitting 50 Workload Tasks..." -ForegroundColor Yellow
Write-Host " • 25 Immediate Tasks (email & report)" -ForegroundColor White
Write-Host " • 10 Delayed Tasks (scheduled 30s in future)" -ForegroundColor White
Write-Host " • 10 Poison / DLQ Tasks (unregistered_type)" -ForegroundColor White
Write-Host " • 5 High Priority Tasks (priority=100)" -ForegroundColor White
Write-Host "============================================================" -ForegroundColor Cyan

$successCount = 0

# 1. 25 Immediate Email & Report Tasks
Write-Host "Submitting 25 Immediate Tasks..." -ForegroundColor Gray
1..25 | ForEach-Object {
    $type = if ($_ % 2 -eq 0) { "email" } else { "report" }
    $body = @{ type = $type; payload = @{ to = "user$_@example.com" }; maxAttempts = 3 } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Uri "http://localhost:8080/tasks" -Method Post -ContentType "application/json" -Body $body | Out-Null
        $script:successCount++
    } catch {
        Write-Host "  Request $_ failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

# 2. 10 Delayed Tasks (30s in future)
Write-Host "Submitting 10 Delayed Tasks (scheduled 30s in future)..." -ForegroundColor Gray
$futureTime = (Get-Date).ToUniversalTime().AddSeconds(30).ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
1..10 | ForEach-Object {
    $body = @{ type = "email"; payload = @{ to = "delayed$_@example.com" }; scheduledAt = $futureTime; maxAttempts = 3 } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Uri "http://localhost:8080/tasks" -Method Post -ContentType "application/json" -Body $body | Out-Null
        $script:successCount++
    } catch {
        Write-Host "  Delayed request $_ failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

# 3. 10 Poison / DLQ Tasks (unregistered_type with maxAttempts=1 -> goes to DLQ)
Write-Host "Submitting 10 Poison / DLQ Tasks..." -ForegroundColor Gray
1..10 | ForEach-Object {
    $body = @{ type = "unregistered_type"; payload = @{ data = "bad_payload_$_" }; maxAttempts = 1 } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Uri "http://localhost:8080/tasks" -Method Post -ContentType "application/json" -Body $body | Out-Null
        $script:successCount++
    } catch {
        Write-Host "  DLQ request $_ failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

# 4. 5 High Priority Tasks (priority=100)
Write-Host "Submitting 5 High Priority Tasks (priority=100)..." -ForegroundColor Gray
1..5 | ForEach-Object {
    $body = @{ type = "email"; payload = @{ to = "vip$_@example.com" }; priority = 100 } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Uri "http://localhost:8080/tasks" -Method Post -ContentType "application/json" -Body $body | Out-Null
        $script:successCount++
    } catch {
        Write-Host "  Priority request $_ failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 100
}

Write-Host "`nTasks Submitted Successfully: $successCount / 50" -ForegroundColor Green

Write-Host "`nWaiting 5 seconds for immediate tasks to process..." -ForegroundColor Gray
Start-Sleep -Seconds 5

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Immediate Execution Results (PostgreSQL Status Breakdown):" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker exec taskqueue-postgres psql -U taskqueue -d taskqueue -c "SELECT status, COUNT(*) FROM tasks GROUP BY status ORDER BY status;"

Write-Host "`nWaiting 25 seconds for the 10 Delayed Tasks (scheduled at +30s) to mature & execute..." -ForegroundColor Gray
Start-Sleep -Seconds 25

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Final Execution Results (After Delayed Tasks Matured):" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
docker exec taskqueue-postgres psql -U taskqueue -d taskqueue -c "SELECT status, COUNT(*) FROM tasks GROUP BY status ORDER BY status;"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Step 6: Fetching Live Prometheus Metrics Summary..." -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
try {
    $metrics = Invoke-RestMethod -Uri "http://localhost:8080/actuator/prometheus"
    $submittedLine = ($metrics -split "`n") | Select-String "taskqueue_tasks_submitted_total" | Select-Object -First 1
    $succeededLine = ($metrics -split "`n") | Select-String "taskqueue_tasks_succeeded_total" | Select-Object -First 1
    $deadLine      = ($metrics -split "`n") | Select-String "taskqueue_tasks_dead_total"      | Select-Object -First 1
    $depthLine     = ($metrics -split "`n") | Select-String "taskqueue_queue_depth"           | Select-Object -First 1

    Write-Host "Metrics Results from API Endpoint:" -ForegroundColor Green
    Write-Host " • $submittedLine" -ForegroundColor White
    Write-Host " • $succeededLine" -ForegroundColor White
    Write-Host " • $deadLine" -ForegroundColor White
    Write-Host " • $depthLine" -ForegroundColor White
} catch {
    Write-Host "Failed to fetch Actuator metrics: $_" -ForegroundColor Red
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " Verification Complete! Watch live on Grafana:" -ForegroundColor Green
Write-Host " • Grafana Dashboard: http://localhost:3000/d/taskqueue-phase4/distributed-task-queue" -ForegroundColor Yellow
Write-Host "============================================================" -ForegroundColor Cyan
