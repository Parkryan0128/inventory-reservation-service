package dev.ryanpark.reservation;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
class PostgresOutboxIT extends OutboxTest {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) { PostgresTestDatabase.configure(registry); }
}
