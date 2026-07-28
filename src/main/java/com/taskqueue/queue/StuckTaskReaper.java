package com.taskqueue.queue;

import com.taskqueue.config.TaskQueueProperties;
import com.taskqueue.repo.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that reclaims tasks stuck in the {@code RUNNING} state past
 * the configured visibility timeout.
 *
 * <p>From task-queues.md: "If a worker dies holding a lease, the lease expires
 * and the task is redelivered. The cost is at-least-once delivery: a task may
 * run more than once. The defense is idempotent handlers."
 *
 * <p>When a worker crashes mid-execution:
 * <ol>
 *   <li>The task row stays in {@code RUNNING} state indefinitely.
 *   <li>After {@code visibilityTimeout} elapses, this reaper transitions the
 *       row to {@code RETRYING} so another worker can pick it up.
 *   <li>The redelivered task will be retried, so handlers must be idempotent.
 * </ol>
 *
 * <p>From ch06: "@Scheduled for recurring jobs such as lease reaping and retry polling."
 *
 * <p>Multi-node consideration: in a multi-node deployment this reaper runs on
 * every node. Since it does a database UPDATE with a WHERE clause, it is safe
 * to run concurrently — multiple reapers racing to reclaim the same row are
 * harmless because only one UPDATE will find the row in RUNNING state.
 */
@Component
public final class StuckTaskReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckTaskReaper.class);

    private final TaskRepository repository;
    private final long visibilityTimeoutSeconds;

    public StuckTaskReaper(
            TaskRepository repository,
            TaskQueueProperties properties) {
        this.repository = repository;
        this.visibilityTimeoutSeconds = properties.reaper().visibilityTimeout().toSeconds();
    }

    /**
     * Runs on a fixed delay, reclaiming tasks stuck in RUNNING.
     *
     * <p>Uses {@code fixedDelayString} (not {@code fixedRateString}): the next
     * run starts only after the previous completes. This prevents overlapping
     * runs if the DB update takes longer than the interval.
     *
     * <p>From ch06: "Use fixedDelay when a new run should wait until the previous
     * run finishes. Use fixedRate only when overlap is harmless or prevented."
     */
    @Scheduled(fixedDelayString = "${taskqueue.reaper.interval:30000}")
    public void reclaimStuckTasks() {
        try {
            int count = repository.reclaimStuckTasks(visibilityTimeoutSeconds);
            if (count > 0) {
                log.warn("[StuckTaskReaper] Reclaimed {} stuck task(s) after {}s visibility timeout",
                        count, visibilityTimeoutSeconds);
            } else {
                log.debug("[StuckTaskReaper] No stuck tasks found");
            }
        } catch (Exception e) {
            // Never let a reaper failure propagate — log and swallow
            log.error("[StuckTaskReaper] Error reclaiming stuck tasks: {}", e.getMessage(), e);
        }
    }
}
