package dev.inventory;

import static org.mockito.Mockito.reset;

import dev.inventory.events.EventPublisher;
import dev.inventory.events.OutboxRepository;
import dev.inventory.inventory.CatalogService;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import dev.inventory.order.ReservationExpiry;
import dev.inventory.order.ReservationRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties =
        "spring.datasource.url=jdbc:h2:mem:inventory_coverage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(InventoryTestSupport.TestTime.class)
abstract class InventoryTestSupport {
  static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired CatalogService catalog;
  @Autowired OrderService service;
  @Autowired OrderPlacement placement;
  @Autowired ReservationRepository orders;
  @Autowired ReservationExpiry expiry;
  @Autowired OutboxRepository outbox;
  @Autowired JdbcTemplate jdbc;
  @Autowired MutableClock clock;
  @Autowired MockMvc mvc;
  @MockitoBean EventPublisher publisher;

  @BeforeEach
  void resetData() {
    jdbc.update("DELETE FROM processed_order_events");
    jdbc.update("DELETE FROM outbox_events");
    jdbc.update("DELETE FROM reservations");
    jdbc.update("DELETE FROM products");
    clock.set(START);
    reset(publisher);
  }

  @TestConfiguration
  static class TestTime {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(START);
    }
  }
}
