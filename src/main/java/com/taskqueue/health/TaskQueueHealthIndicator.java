package com.taskqueue.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import com.taskqueue.repo.DeadLetterRepository;
import com.taskqueue.repo.TaskRepository;

/**
 * Custom Actuator health indicator for the task queue.
 *
 * <p>From chapter-07: "Health indicators let load balancers and orchestrators
 * (Kubernetes readiness probes) know when an instance should stop receiving traffic.
 * A custom health indicator adds domain-specific checks beyond DB connectivity."
 *
 * <p>This indicator reports {@code DOWN} when the DLQ is growing rapidly enough
 * to suggest a systemic issue. It provides queue depth and DLQ count as details
 * so operators have context without opening Prometheus.
 *
 * <p>Exposed at: {@code GET /actuator/health/taskQueue}
 *
 * <h2>Thresholds (configurable in future)</h2>
 * <ul>
 *   <li>DLQ entries > 1000 → {@code OUT_OF_SERVICE} — systemic failure
 *   <li>DLQ entries > 100  → {@code DOWN} — elevated failures, investigate
 *   <li>Otherwise          → {@code UP}
 * </ul>
 */
@Component("taskQueue")
public class TaskQueueHealthIndicator implements HealthIndicator {

    private static final int DLQ_DOWN_THRESHOLD        = 100;
    private static final int DLQ_OUT_OF_SERVICE_THRESHOLD = 1000;

    private final TaskRepository taskRepository;
    private final DeadLetterRepository dlqRepository;

    public TaskQueueHealthIndicator(
            TaskRepository taskRepository,
            DeadLetterRepository dlqRepository) {
        this.taskRepository = taskRepository;
        this.dlqRepository  = dlqRepository;
    }

    @Override
    public Health health() {
        try {
            int queueDepth = taskRepository.pendingCount();
            int dlqSize    = dlqRepository.count();

            Health.Builder builder = Health.up()
                    .withDetail("queueDepth", queueDepth)
                    .withDetail("dlqSize", dlqSize);

            if (dlqSize >= DLQ_OUT_OF_SERVICE_THRESHOLD) {
                return builder.outOfService()
                        .withDetail("reason", "DLQ critically large — systemic failure suspected")
                        .build();
            }

            if (dlqSize >= DLQ_DOWN_THRESHOLD) {
                return builder.down()
                        .withDetail("reason", "DLQ growing — elevated failure rate, investigate")
                        .build();
            }

            return builder.build();

        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("reason", "Failed to query task queue state")
                    .build();
        }
    }
}
