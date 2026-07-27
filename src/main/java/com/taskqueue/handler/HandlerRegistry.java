package com.taskqueue.handler;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry mapping task {@code type} strings to {@link TaskHandler} implementations.
 *
 * <p>In Phase 1, this was built from a collection of {@link TaskRegistration} records
 * (a manual map). In Phase 2, Spring auto-discovers all {@code @Component} handler
 * beans and injects them as a {@code List<TaskHandler>}. The registry merges both
 * sources so Phase 1 registrations and Phase 2 beans coexist.
 *
 * <p>From ch01 material: "Handlers become independently discoverable — new handler
 * components are registered here without a manual registry entry."
 *
 * <p>The OCP seam (solid.md): adding a new task type means creating a new
 * {@code @Component} that implements {@link TaskHandler#supportedType()} —
 * the Worker and Registry never change.
 */
public final class HandlerRegistry {

    private final Map<String, TaskHandler> handlers;

    /**
     * Phase 1 constructor: builds the registry from explicit {@link TaskRegistration} records.
     * Used when Spring is not managing handlers (e.g., the legacy {@code App.java}).
     */
    public HandlerRegistry(Collection<TaskRegistration> registrations) {
        this.handlers = new HashMap<>();
        for (TaskRegistration reg : registrations) {
            String type = normalise(reg.taskType());
            if (handlers.containsKey(type)) {
                throw new IllegalArgumentException(
                        "Duplicate registration for task type: " + type);
            }
            handlers.put(type, reg.handler());
        }
    }

    /**
     * Phase 2 constructor: Spring injects all {@code TaskHandler} beans discovered
     * by component scanning. Each handler declares its type via {@link TaskHandler#supportedType()}.
     *
     * <p>Handlers with blank {@link TaskHandler#supportedType()} (e.g., lambdas used
     * in tests) are silently skipped — they must be registered via the other constructor.
     *
     * @param handlers Spring-injected list of all TaskHandler beans
     */
    public HandlerRegistry(List<TaskHandler> handlers) {
        this.handlers = handlers.stream()
                .filter(h -> !h.supportedType().isBlank())
                .collect(Collectors.toUnmodifiableMap(
                        h -> normalise(h.supportedType()),
                        h -> h,
                        (existing, replacement) -> {
                            throw new IllegalStateException(
                                    "Duplicate TaskHandler beans for type: "
                                            + replacement.supportedType());
                        }));
    }

    /**
     * Finds the handler for the given task type.
     *
     * @param taskType the type string (case-insensitive)
     * @return the handler if registered, or empty
     */
    public Optional<TaskHandler> find(String taskType) {
        if (taskType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(normalise(taskType)));
    }

    /**
     * Returns true if a handler is registered for the given type.
     * Used by the custom {@code @KnownTaskType} validator.
     */
    public boolean supports(String taskType) {
        return find(taskType).isPresent();
    }

    public int size() {
        return handlers.size();
    }

    /** Normalises type strings: trims whitespace and upper-cases for map keys. */
    private static String normalise(String type) {
        return type.trim().toUpperCase();
    }
}
