package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.ryanpark.reservation.demo.DemoService;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.ProductView;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import dev.ryanpark.reservation.order.OrderPlacement;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderStatus;
import dev.ryanpark.reservation.order.Reservation;
import dev.ryanpark.reservation.order.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoIdempotencyRecoveryTest {
  private final UUID productId = UUID.randomUUID();
  private final String owner = "demo-" + productId;
  private final OrderService orders = mock(OrderService.class);
  private final ReservationRepository reservations = mock(ReservationRepository.class);

  @Test
  void scenarioRecoversUniqueKeyCollisionsThroughTheSameEntryPointAsTheApi() throws Exception {
    var winner = mock(Reservation.class);
    var orderId = UUID.randomUUID();
    when(winner.id()).thenReturn(orderId);
    when(winner.ownerId()).thenReturn(owner);
    when(winner.productId()).thenReturn(productId);
    when(winner.quantity()).thenReturn(3);
    when(winner.unitPriceCents()).thenReturn(100L);
    when(winner.currency()).thenReturn("CAD");
    when(winner.status()).thenReturn(OrderStatus.RESERVED);
    when(winner.createdAt()).thenReturn(Instant.now());
    when(winner.expiresAt()).thenReturn(Instant.now().plusSeconds(120));
    when(reservations.findByOwnerIdAndIdempotencyKey(owner, "same-key"))
        .thenReturn(Optional.of(winner));

    var result = scenario().run("idempotency");

    assertThat(result.passed()).isTrue();
    assertThat(result.outcomes())
        .containsEntry("RESERVED", 16L)
        .containsEntry("IDEMPOTENCY_CONFLICT", 1L);
    assertThat(result.attempts().subList(0, 16))
        .allSatisfy(reply -> assertThat(reply.orderId()).isEqualTo(orderId));
    assertThat(result.persistedOrders()).isEqualTo(1);
    verify(reservations, times(17)).findByOwnerIdAndIdempotencyKey(owner, "same-key");
  }

  @Test
  void integrityErrorsWithoutACommittedWinnerStillFailTheScenario() {
    when(reservations.findByOwnerIdAndIdempotencyKey(owner, "same-key"))
        .thenReturn(Optional.empty());
    var demo = scenario();

    assertThatThrownBy(() -> demo.run("idempotency"))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(DataIntegrityViolationException.class);
    assertThat(demo.busy()).isFalse();
  }

  private DemoService scenario() {
    // Force the rare collision rather than depending on H2 thread scheduling to reproduce it.
    when(orders.reserve(eq(owner), eq("same-key"), any(ReserveRequest.class)))
        .thenThrow(new DataIntegrityViolationException("Unique owner/idempotency key collision"));
    var catalog = mock(CatalogService.class);
    var initial = new ProductView(productId, "DEMO-RECOVERY", "Recovery", 100, "CAD", 10, 0, 0, 10);
    var reserved = new ProductView(productId, "DEMO-RECOVERY", "Recovery", 100, "CAD", 7, 3, 0, 10);
    when(catalog.create(any())).thenReturn(initial);
    when(catalog.get(productId)).thenReturn(initial, reserved);
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, productId))
        .thenReturn(1L);
    return new DemoService(
        catalog, orders, new OrderPlacement(orders, reservations), jdbc, Clock.systemUTC());
  }
}
