package dev.ryanpark.reservation;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresTestDatabase {
    private static final PostgreSQLContainer<?> DATABASE;
    static {
        // Docker is a required acceptance gate. No disabledWithoutDocker / silent skip.
        DATABASE = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("reservations").withUsername("test").withPassword("test-password");
        DATABASE.start();
    }
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    private PostgresTestDatabase() {}
}
