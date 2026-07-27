package com.taskqueue.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskqueue.common.Result;
import com.taskqueue.model.Task;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * Concrete handler for {@code type = "report"}.
 *
 * <p>Simulates generating a PDF report. In production this would call a
 * report rendering service or library. Here it uses a random failure to
 * make the handler registry non-trivial to test with multiple types.
 *
 * <p>Having two concrete handlers proves that the {@link HandlerRegistry}
 * correctly routes by type and that both handlers co-exist as Spring beans
 * without ambiguity (each has a distinct {@link #supportedType()}).
 */
@Component
public final class ReportTaskHandler implements TaskHandler {

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    public ReportTaskHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String supportedType() {
        return "report";
    }

    @Override
    public Result<Void> handle(Task task) throws Exception {
        // Parse payload to extract report parameters
        JsonNode payload = objectMapper.readTree(task.payload());
        String reportId = payload.path("reportId").asText("unknown");

        // Simulate longer processing time (100–300 ms)
        Thread.sleep(100 + random.nextInt(200));

        // Simulate 5% permanent failure (e.g., missing template)
        if (random.nextInt(20) == 0) {
            return Result.fail("Report template not found for report " + reportId);
        }

        System.out.printf("[ReportTaskHandler] Generated report '%s' for task %s%n",
                reportId, task.id());
        return Result.ok(null);
    }
}
