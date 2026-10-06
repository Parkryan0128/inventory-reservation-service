package dev.inventory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

final class PostgresTestDatabase {
  private static final PostgreSQLContainer<?> DATABASE;

  static {
    // Docker is a required acceptance gate. No disabledWithoutDocker / silent skip.
    DATABASE =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("reservations")
            .withUsername("test")
            .withPassword("test-password");
    DATABASE.start();
  }

  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  static void configureIsolated(DynamicPropertyRegistry registry) {
    configure(registry);
    var schema = "coverage_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection =
            DriverManager.getConnection(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    } catch (SQLException error) {
      throw new IllegalStateException("Cannot create test schema", error);
    }
    var url = DATABASE.getJdbcUrl();
    registry.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
  }

  private PostgresTestDatabase() {}
}
