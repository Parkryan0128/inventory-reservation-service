package dev.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.inventory.common.ApiException;
import dev.inventory.demo.DemoService;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import dev.inventory.order.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoLifecycleChecksTest {
  private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
  private static final Map<OrderStatus, String> CHECKS =
      Map.of(
          OrderStatus.CONFIRMED, "Repeated payment sells only once",
          OrderStatus.CANCELLED, "Repeated cancellation restores only once",
          OrderStatus.PAYMENT_FAILED, "Repeated payment failure restores only once");

  private final UUID productId = UUID.randomUUID();
  private final String owner = "demo-" + productId;
  private final Map<String, UUID> ids =
      Map.of(
          "purchase", UUID.randomUUID(), "cancel", UUID.randomUUID(), "failure", UUID.randomUUID());
  private final CatalogService catalog = mock(CatalogService.class);
  private final OrderService orders = mock(OrderService.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @Test
  void matchingTerminalRepliesAndBalancedStockPass() throws Exception {
    assertThat(scenario().run("lifecycle").passed()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({
    "CONFIRMED, rejection", "CANCELLED, rejection", "PAYMENT_FAILED, rejection",
    "CONFIRMED, wrong-order", "CANCELLED, wrong-order", "PAYMENT_FAILED, wrong-order",
    "CONFIRMED, wrong-status", "CANCELLED, wrong-status", "PAYMENT_FAILED, wrong-status"
  })
  void correctStockCannotHideABrokenRepeatedTransition(OrderStatus status, String fault)
      throws Exception {
    var demo = scenario();
    var key =
        switch (status) {
          case CONFIRMED -> "purchase";
          case CANCELLED -> "cancel";
          default -> "failure";
        };
    var id = ids.get(key);
    var first = order(id, status, status == OrderStatus.CONFIRMED ? 3 : 2);
    var repeat =
        switch (status) {
          case CONFIRMED -> when(orders.payment(id, true));
          case CANCELLED -> when(orders.cancel(owner, id));
          default -> when(orders.payment(id, false));
        };
    repeat = repeat.thenReturn(first);
    if (fault.equals("rejection")) {
      repeat.thenThrow(ApiException.conflict("INVALID_TRANSITION", "Repeated action rejected"));
    } else {
      repeat.thenReturn(
          order(
              fault.equals("wrong-order") ? UUID.randomUUID() : id,
              fault.equals("wrong-status") ? OrderStatus.RESERVED : status,
              first.quantity()));
    }

    var result = demo.run("lifecycle");
    assertThat(result.passed()).isFalse();
    assertThat(result.checks().get(CHECKS.get(status))).isFalse();
    assertThat(result.checks().get("Stock stays non-negative and balanced at every snapshot"))
        .isTrue();
    assertThat(result.snapshots().getLast().inventory()).isEqualTo(stock(9, 0, 3));
  }

  private DemoService scenario() {
    when(catalog.create(any())).thenReturn(stock(12, 0, 0));
    when(catalog.get(productId))
        .thenReturn(
            stock(12, 0, 0),
            stock(9, 3, 0),
            stock(9, 0, 3),
            stock(7, 2, 3),
            stock(9, 0, 3),
            stock(7, 2, 3),
            stock(9, 0, 3));
    when(orders.reserve(eq(owner), anyString(), any(ReserveRequest.class)))
        .thenAnswer(
            call ->
                order(
                    ids.get(call.getArgument(1)),
                    OrderStatus.RESERVED,
                    call.<ReserveRequest>getArgument(2).quantity()));
    var paid = order(ids.get("purchase"), OrderStatus.CONFIRMED, 3);
    var cancelled = order(ids.get("cancel"), OrderStatus.CANCELLED, 2);
    var failed = order(ids.get("failure"), OrderStatus.PAYMENT_FAILED, 2);
    when(orders.payment(paid.id(), true)).thenReturn(paid);
    when(orders.cancel(owner, cancelled.id())).thenReturn(cancelled);
    when(orders.payment(failed.id(), false)).thenReturn(failed);
    when(orders.get(owner, cancelled.id())).thenReturn(cancelled);
    when(orders.get(owner, failed.id())).thenReturn(failed);
    when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, productId))
        .thenReturn(3L);
    return new DemoService(
        catalog,
        orders,
        new OrderPlacement(orders, mock(ReservationRepository.class)),
        jdbc,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private ProductView stock(int available, int reserved, int sold) {
    return new ProductView(
        productId, "DEMO-CHECKS", "Checks", 100, "CAD", available, reserved, sold, 12);
  }

  private OrderView order(UUID id, OrderStatus status, int quantity) {
    return new OrderView(
        id,
        owner,
        productId,
        quantity,
        100,
        100L * quantity,
        "CAD",
        status,
        NOW,
        NOW.plusSeconds(120));
  }
}
