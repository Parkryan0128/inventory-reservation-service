package dev.ryanpark.reservation;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
class PostgresInventoryIT extends InventoryTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) { PostgresTestDatabase.configure(registry); }
}
