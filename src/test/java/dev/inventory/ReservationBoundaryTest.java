package dev.inventory;

import static org.assertj.core.api.Assertions.*;

import dev.inventory.common.ApiException;
import dev.inventory.events.OutboxEvent;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationBoundaryTest extends InventoryTestSupport {
  @ParameterizedTest
  @CsvSource({"-1, CONFIRMED", "0, EXPIRED", "1, EXPIRED"})
  void paymentChecksTheExactExpiryInstant(long offsetMillis, OrderStatus expected) {
    var product = TestProducts.create(catalog, 2);
    var order = placement.place("alice", "deadline", new ReserveRequest(product.id(), 2));
    clock.set(order.expiresAt().plusMillis(offsetMillis));

    assertThat(service.payment(order.id(), true).status()).isEqualTo(expected);
    var stock = catalog.get(product.id());
    assertThat(stock.reserved()).isZero();
    assertThat(stock.available()).isEqualTo(expected == OrderStatus.EXPIRED ? 2 : 0);
    assertThat(stock.sold()).isEqualTo(expected == OrderStatus.CONFIRMED ? 2 : 0);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::eventType)
        .containsExactly("RESERVED", expected.name());
  }

  @Test
  void expiryDoesNothingBeforeDeadlineAndReleasesStockOnceAtDeadline() {
    var product = TestProducts.create(catalog, 2);
    var order = service.reserve("alice", new ReserveRequest(product.id(), 2));
    clock.set(order.expiresAt().minusMillis(1));
    assertThat(service.expire(order.id())).isFalse();
    assertThat(catalog.get(product.id()).reserved()).isEqualTo(2);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id())).hasSize(1);

    clock.set(order.expiresAt());
    assertThat(service.expire(order.id())).isTrue();
    assertThat(service.expire(order.id())).isFalse();
    assertThat(catalog.get(product.id()).available()).isEqualTo(2);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::eventType)
        .containsExactly("RESERVED", "EXPIRED");
  }

  @ParameterizedTest
  @EnumSource(value = OrderStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
  void replayOfTerminalOrderDoesNotReserveAgainOrEmitEvents(OrderStatus status) {
    var product = TestProducts.create(catalog, 4);
    var request = new ReserveRequest(product.id(), 2);
    var order = placement.place("alice", "original", request);
    switch (status) {
      case CONFIRMED -> service.payment(order.id(), true);
      case CANCELLED -> service.cancel("alice", order.id());
      case PAYMENT_FAILED -> service.payment(order.id(), false);
      case EXPIRED -> {
        clock.set(order.expiresAt());
        service.expire(order.id());
      }
      default -> throw new AssertionError(status);
    }
    var before = catalog.get(product.id());
    var completed = service.get("alice", order.id());
    var eventIds =
        outbox.findByOrderIdOrderByRevisionAsc(order.id()).stream().map(OutboxEvent::id).toList();

    assertThat(placement.place("alice", "original", request)).isEqualTo(completed);
    assertThat(placement.place("alice", "original", request).status()).isEqualTo(status);
    assertThatThrownBy(
            () -> placement.place("alice", "original", new ReserveRequest(product.id(), 1)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThat(catalog.get(product.id())).isEqualTo(before);
    assertThat(orders.countByProductId(product.id())).isEqualTo(1);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::id)
        .containsExactlyElementsOf(eventIds);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 10_000})
  void validQuantityBoundariesReserveAndConfirmExactStock(int quantity) {
    var product = TestProducts.create(catalog, quantity, 1_000_000_000L, "CAD");
    var order =
        placement.place(
            "a".repeat(100), "k".repeat(80), new ReserveRequest(product.id(), quantity));
    assertThat(order.totalPriceCents()).isEqualTo(1_000_000_000L * quantity);
    assertThat(order.currency()).isEqualTo("CAD");
    assertThat(catalog.get(product.id()).available()).isZero();
    service.payment(order.id(), true);
    assertThat(catalog.get(product.id()).sold()).isEqualTo(quantity);
    assertThat(catalog.get(product.id()).reserved()).isZero();
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 10_001, Integer.MAX_VALUE})
  void invalidQuantityLeavesStockOrdersAndEventsUntouched(int quantity) {
    var product = TestProducts.create(catalog, 20_000);
    assertThatThrownBy(
            () -> placement.place("alice", "invalid", new ReserveRequest(product.id(), quantity)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("INVALID_REQUEST"));
    assertThat(catalog.get(product.id())).isEqualTo(product);
    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
  }

  @Test
  void invalidOwnerKeyAndMissingProductCannotWrite() {
    var product = TestProducts.create(catalog, 2);
    var request = new ReserveRequest(product.id(), 1);
    for (String owner : new String[] {null, "", " ", "a".repeat(101)}) {
      assertThatThrownBy(() -> placement.place(owner, "valid", request))
          .isInstanceOf(ApiException.class);
    }
    for (String key : new String[] {null, "", "k".repeat(81), "has space", "é", "line\nbreak"}) {
      assertThatThrownBy(() -> placement.place("alice", key, request))
          .isInstanceOf(ApiException.class);
    }
    assertThatThrownBy(
            () -> placement.place("alice", "valid", new ReserveRequest(UUID.randomUUID(), 1)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("NOT_FOUND"));
    assertThat(catalog.get(product.id())).isEqualTo(product);
    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
  }

  @Test
  void expiryProcessesFiftyDueOrdersThenTheRemainder() {
    var product = TestProducts.create(catalog, 52);
    var due = new ArrayList<UUID>();
    for (int i = 0; i < 51; i++) {
      due.add(service.reserve("alice", new ReserveRequest(product.id(), 1)).id());
      clock.advance(Duration.ofSeconds(1));
    }
    var future = service.reserve("alice", new ReserveRequest(product.id(), 1));
    clock.set(service.get("alice", due.getLast()).expiresAt());

    assertThat(expiry.expireDue()).isEqualTo(50);
    for (var id : due.subList(0, 50)) {
      assertThat(service.get("alice", id).status()).isEqualTo(OrderStatus.EXPIRED);
    }
    assertThat(service.get("alice", due.getLast()).status()).isEqualTo(OrderStatus.RESERVED);
    assertThat(expiry.expireDue()).isEqualTo(1);
    assertThat(expiry.expireDue()).isZero();
    assertThat(service.get("alice", future.id()).status()).isEqualTo(OrderStatus.RESERVED);
    var stock = catalog.get(product.id());
    assertThat(stock.available()).isEqualTo(51);
    assertThat(stock.reserved()).isEqualTo(1);
    assertThat(stock.sold()).isZero();
    assertThat(outbox.count()).isEqualTo(103);
  }
}
