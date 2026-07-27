package com.taskqueue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 2 entry point.
 *
 * <p>Replaces the manual {@code App.java} wiring from Phase 1.
 * Spring builds the full object graph from @Bean methods and @Component classes.
 *
 * <p>Architecture impact:
 * <pre>
 *   Phase 1: main() -> new InMemoryTaskQueue -> new WorkerPool -> new Worker
 *   Phase 2: SpringApplication -> ApplicationContext -> QueueConfig beans
 *                              -> WorkerPoolLifecycle.start() -> WorkerPool
 * </pre>
 *
 * <p>Keep {@code App.java} in the source tree as a Phase 1 reference.
 * This class is the active main.
 */
@SpringBootApplication
@ConfigurationPropertiesScan   // discovers TaskQueueProperties automatically
@EnableAsync                   // enables @Async proxy support
@EnableScheduling              // enables @Scheduled (StuckTaskReaper, etc.)
public class TaskQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskQueueApplication.class, args);
    }
}
