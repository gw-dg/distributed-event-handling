package com.taskqueue;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Abstract base class for integration tests that require a real PostgreSQL database.
 *
 * <p>From ch05: "Do not mock Postgres for SQL behavior. The locking, JSONB, indexes,
 * and timestamp behavior are exactly what you need to test."
 *
 * <p>Testcontainers spins a real Postgres 16 container in Docker. Spring caches the
 * application context across test classes that share the same configuration, so the
 * container is started once per test suite run (not once per test method).
 *
 * <p>All integration test classes that need a real DB should extend this class.
 * They get:
 * <ul>
 *   <li>A running PostgreSQL 16 container with {@code @DynamicPropertySource} datasource config.
 *   <li>A full Spring context with Flyway migrations already applied.
 *   <li>All Spring beans available via {@code @Autowired}.
 * </ul>
 *
 * <p>Note: this requires Docker to be running on the CI/developer machine.
 * See docker-compose.yml for the local development database.
 */
@SpringBootTest
public abstract class PostgresIT {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("taskqueue_test")
                .withUsername("test")
                .withPassword("test");
        postgres.start();
    }

    /**
     * Injects the container's dynamic JDBC URL, username, and password into the
     * Spring property sources before the application context starts.
     *
     * <p>This overrides {@code spring.datasource.*} from {@code application.yml}
     * with the container's actual address and port.
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Force postgres queue type for all integration tests
        registry.add("taskqueue.queue.type", () -> "postgres");
    }
}
