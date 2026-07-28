package com.taskqueue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Abstract base class for Phase 4 integration tests that need BOTH Postgres AND Redis.
 *
 * <p>Uses the singleton pattern (static containers started once per JVM) to avoid
 * the significant Docker overhead of starting containers per test class.
 *
 * <p>Tests that only need Postgres should extend {@link PostgresIT}.
 * Tests that need both should extend this class.
 */
@SpringBootTest
public abstract class PostgresRedisIT {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("taskqueue_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // These tests use the redis-stream queue type
        registry.add("taskqueue.queue.type",       () -> "redis-stream");
        registry.add("taskqueue.event-bus.type",   () -> "in-process");
        // Use a local Redis — tests must start a Redis container themselves
        // or set spring.redis.host/port to an already-running instance.
    }
}
