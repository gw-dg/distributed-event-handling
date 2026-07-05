package main.java.com.taskqueue.handler;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class HandlerRegistry {
    private final Map<String, TaskHandler> handlers;

    public HandlerRegistry(Collection<TaskRegistration> registrations) {
        this.handlers = new HashMap<>();

        for (TaskRegistration registration : registrations) {
            String type = registration.taskType().trim().toUpperCase();

            if (handlers.containsKey(type)) {
                throw IllegalArgumentException("Duplicate registration for existing handler type " + type);
            }

            handlers.put(type, registration.handler());
        }
    }

    public Optional<TaskHandler> find(String taskType) {

        if (taskType == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(
                handlers.get(taskType.trim().toUpperCase()));
    }

    public boolean supports(String taskType) {
        return find(taskType).isPresent();
    }

    public int size() {
        return handlers.size();
    }

}
