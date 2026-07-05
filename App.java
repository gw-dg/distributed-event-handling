import java.util.List;

import main.java.com.taskqueue.api.TaskSubmissionService;
import main.java.com.taskqueue.common.Result;
import main.java.com.taskqueue.handler.HandlerRegistry;
import main.java.com.taskqueue.handler.TaskRegistration;
import main.java.com.taskqueue.queue.InMemoryTaskQueue;
import main.java.com.taskqueue.queue.TaskQueue;
import main.java.com.taskqueue.worker.WorkerPool;

public final class App {

    public static void main(String[] args) throws Exception {

        TaskQueue queue = new InMemoryTaskQueue(100);

        HandlerRegistry registry = new HandlerRegistry(
                List.of(

                        new TaskRegistration(
                                "EMAIL",
                                task -> {
                                    System.out.println(
                                            "Sending email: "
                                                    + task.payload());

                                    Thread.sleep(200);

                                    return Result.ok(null);
                                }),

                        new TaskRegistration(
                                "FAIL",
                                task -> Result.fail(
                                        "Simulated permanent failure")),

                        new TaskRegistration(
                                "RETRY",
                                task -> Result.retryable(
                                        "Temporary downstream outage"))));

        WorkerPool pool = new WorkerPool(
                4,
                queue,
                registry);

        pool.start();

        TaskSubmissionService api = new TaskSubmissionService(queue);

        for (int i = 0; i < 10; i++) {

            api.submit(
                    "EMAIL",
                    """
                            {
                                "to":"user%d@example.com"
                            }
                            """.formatted(i));
        }

        api.submit("FAIL", "{}");
        api.submit("RETRY", "{}");

        Thread.sleep(3000);

        pool.shutdown();

        System.out.println();
        System.out.println("Processed = " + pool.processedCount());
        System.out.println("Failed    = " + pool.failedCount());
        System.out.println("Remaining = " + queue.size());
    }
}