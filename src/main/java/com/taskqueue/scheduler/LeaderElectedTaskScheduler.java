package com.taskqueue.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.taskqueue.domain.TaskEvent;
import com.taskqueue.domain.TaskEventType;
import com.taskqueue.model.Task;
import com.taskqueue.outbox.OutboxRecord;
import com.taskqueue.outbox.OutboxRepository;
import com.taskqueue.port.LeaderElector;
import com.taskqueue.repo.TaskRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Leader-elected scheduler: polls due tasks and writes them to the outbox for relay.
 */
public class LeaderElectedTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectedTaskScheduler.class);
    private static final String SCHEDULER_ROLE = "scheduler";

    private final TaskRepository taskRepository;
    private final OutboxRepository outboxRepository;
    private final LeaderElector leaderElector;
    private final ObjectMapper mapper;

    public LeaderElectedTaskScheduler(
            TaskRepository taskRepository,
            OutboxRepository outboxRepository,
            LeaderElector leaderElector,
            ObjectMapper mapper) {
        this.taskRepository  = taskRepository;
        this.outboxRepository = outboxRepository;
        this.leaderElector   = leaderElector;
        this.mapper          = mapper;
    }

    /**
     * Polls due tasks and writes outbox rows every 500ms.
     * Only the leader node runs this logic.
     */
    @Scheduled(fixedDelayString = "500")
    @Transactional
    public void scheduleDueTasks() {
        if (!leaderElector.isLeader(SCHEDULER_ROLE)) {
            return;
        }

        List<Task> dueTasks = taskRepository.pollDue(100);
        if (dueTasks.isEmpty()) {
            return;
        }

        int scheduled = 0;
        for (Task task : dueTasks) {
            try {
                String payload = mapper.writeValueAsString(
                        TaskEvent.of(task, TaskEventType.SUBMITTED, null));
                outboxRepository.insert(
                        OutboxRecord.create(task.id(), TaskEventType.SUBMITTED.name(), payload));
                scheduled++;
            } catch (Exception e) {
                log.error("[Scheduler] Failed to write outbox for task {}: {}",
                        task.id(), e.getMessage());
            }
        }

        log.info("[Scheduler] Scheduled {} due tasks into outbox", scheduled);
    }
}
