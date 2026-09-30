package dev.ryanpark.reservation;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class PostgresManualDemoIT extends ManualDemoTest {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.configure(registry);
  }
}
