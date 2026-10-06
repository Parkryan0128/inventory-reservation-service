package dev.inventory;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class PostgresInventoryQueryIT extends InventoryQueryTest {
  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.configureIsolated(registry);
  }
}
