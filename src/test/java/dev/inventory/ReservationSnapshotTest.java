package dev.inventory;

import static org.assertj.core.api.Assertions.*;

import dev.inventory.common.ApiException;
import dev.inventory.events.OrderChanged;
import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderStatus;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

@RecordApplicationEvents
class ReservationSnapshotTest extends InventoryTestSupport {
  @Autowired ApplicationEvents changes;

  @ParameterizedTest
  @EnumSource(value = OrderStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
  void completingOneHoldLeavesTheOtherOwnersUnitsReserved(OrderStatus target) {
    var product = TestProducts.create(catalog, 6);
    var request = new ReserveRequest(product.id(), 2);
    var alice = placement.place("alice", "checkout", request);
    clock.advance(Duration.ofSeconds(1));
    var bob = placement.place("bob", "checkout", new ReserveRequest(product.id(), 3));

    OrderView completed =
        switch (target) {
          case CONFIRMED -> service.payment(alice.id(), true);
          case CANCELLED -> service.cancel("alice", alice.id());
          case PAYMENT_FAILED -> service.payment(alice.id(), false);
          case EXPIRED -> {
            clock.set(alice.expiresAt());
            assertThat(expiry.expireDue()).isOne();
            yield service.get("alice", alice.id());
          }
          default -> throw new AssertionError(target);
        };
    assertThat(completed.status()).isEqualTo(target);
    assertThat(placement.place("alice", "checkout", request)).isEqualTo(completed);
    assertThat(service.get("bob", bob.id())).isEqualTo(bob);
    int sold = target == OrderStatus.CONFIRMED ? 2 : 0;
    var afterAlice = stock(product, 3 - sold, 3, sold);
    assertThat(catalog.get(product.id())).isEqualTo(afterAlice);

    var paid = service.payment(bob.id(), true);
    assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
    var afterBob = stock(product, 3 - sold, 0, 3 + sold);
    assertThat(catalog.get(product.id())).isEqualTo(afterBob);
    assertThat(changes.stream(OrderChanged.class))
        .containsExactly(
            new OrderChanged(stock(product, 4, 2, 0), alice),
            new OrderChanged(stock(product, 1, 5, 0), bob),
            new OrderChanged(afterAlice, completed),
            new OrderChanged(afterBob, paid));
  }

  @Test
  void aRejectedHoldEmitsNoSnapshotAndCanBeRetriedAfterARelease() {
    var product = TestProducts.create(catalog, 2);
    var request = new ReserveRequest(product.id(), 2);
    var first = placement.place("alice", "alice-cart", request);
    assertThatThrownBy(() -> placement.place("bob", "bob-cart", request))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_STOCK"));
    var cancelled = service.cancel("alice", first.id());
    var retry = placement.place("bob", "bob-cart", request);
    assertThat(retry.ownerId()).isEqualTo("bob");
    assertThat(retry.id()).isNotEqualTo(first.id());
    var held = stock(product, 0, 2, 0);
    assertThat(changes.stream(OrderChanged.class))
        .containsExactly(
            new OrderChanged(held, first),
            new OrderChanged(product, cancelled),
            new OrderChanged(held, retry));
    assertThat(catalog.get(product.id())).isEqualTo(held);
    assertThat(orders.count()).isEqualTo(2);
    assertThat(outbox.count()).isEqualTo(3);
  }

  private ProductView stock(ProductView product, int available, int reserved, int sold) {
    return new ProductView(
        product.id(),
        product.sku(),
        product.name(),
        product.priceCents(),
        product.currency(),
        available,
        reserved,
        sold,
        product.initialStock());
  }
}
