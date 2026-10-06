package dev.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderStatus;
import java.sql.Connection;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class PostgresReservationBoundaryIT extends ReservationBoundaryTest {
  @Autowired DataSource dataSource;

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.configureIsolated(registry);
  }

  @Test
  void paymentRechecksExpiryAfterWaitingForProductLock() throws Exception {
    var product = TestProducts.create(catalog, 1);
    var order = placement.place("alice", "lock-wait", new ReserveRequest(product.id(), 1));
    try (var workers = Executors.newSingleThreadExecutor()) {
      java.util.concurrent.Future<dev.inventory.order.OrderDtos.OrderView> payment;
      try (Connection locked = dataSource.getConnection()) {
        locked.setAutoCommit(false);
        try {
          int blocker;
          try (var statement =
              locked.prepareStatement(
                  "SELECT pg_backend_pid() FROM products WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, product.id());
            try (var rows = statement.executeQuery()) {
              assertThat(rows.next()).isTrue();
              blocker = rows.getInt(1);
            }
          }
          payment = workers.submit(() -> service.payment(order.id(), true));
          Awaitility.await()
              .atMost(5, TimeUnit.SECONDS)
              .untilAsserted(
                  () ->
                      assertThat(
                              jdbc.queryForObject(
                                  "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                                  Boolean.class,
                                  blocker))
                          .isTrue());
          clock.set(order.expiresAt());
        } finally {
          locked.rollback();
        }
      }
      assertThat(payment.get(10, TimeUnit.SECONDS).status()).isEqualTo(OrderStatus.EXPIRED);
    }
    assertThat(catalog.get(product.id()).available()).isEqualTo(1);
    assertThat(catalog.get(product.id()).sold()).isZero();
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(dev.inventory.events.OutboxEvent::eventType)
        .containsExactly("RESERVED", "EXPIRED");
  }
}
