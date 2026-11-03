package dev.inventory;

import static org.assertj.core.api.Assertions.*;

import dev.inventory.common.ApiException;
import dev.inventory.events.OutboxEvent;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderStatus;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationTransactionTest extends InventoryTestSupport {
  @Autowired PlatformTransactionManager transactions;

  @ParameterizedTest
  @EnumSource(value = OrderStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
  void rolledBackCompletionRestoresStockRevisionAndOutbox(OrderStatus target) {
    var product = TestProducts.create(catalog, 5);
    var order =
        placement.place("alice", "rollback-completion", new ReserveRequest(product.id(), 2));
    if (target == OrderStatus.EXPIRED) clock.set(order.expiresAt());
    var reservedStock = catalog.get(product.id());
    var originalEvent = outbox.findByOrderIdOrderByRevisionAsc(order.id()).getFirst();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              assertThat(complete(order.id(), target).status()).isEqualTo(target);
              assertThat(orders.findById(order.id()).orElseThrow().revision()).isEqualTo(2);
              assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id())).hasSize(2);
              assertThat(catalog.get(product.id()).reserved()).isZero();
              transaction.setRollbackOnly();
            });

    assertThat(service.get("alice", order.id())).isEqualTo(order);
    assertThat(orders.findById(order.id()).orElseThrow().revision()).isEqualTo(1);
    assertThat(catalog.get(product.id())).isEqualTo(reservedStock);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::id)
        .containsExactly(originalEvent.id());

    assertThat(complete(order.id(), target).status()).isEqualTo(target);
    var stock = catalog.get(product.id());
    assertThat(stock.reserved()).isZero();
    assertThat(stock.available()).isEqualTo(target == OrderStatus.CONFIRMED ? 3 : 5);
    assertThat(stock.sold()).isEqualTo(target == OrderStatus.CONFIRMED ? 2 : 0);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::eventType, OutboxEvent::revision)
        .containsExactly(tuple("RESERVED", 1), tuple(target.name(), 2));
  }

  @ParameterizedTest
  @MethodSource("terminalTransitions")
  void terminalOrdersPreserveStockRevisionAndEvents(OrderStatus current, OrderStatus requested) {
    var product = TestProducts.create(catalog, 5);
    var order = placement.place("alice", "terminal", new ReserveRequest(product.id(), 2));
    if (current == OrderStatus.EXPIRED) clock.set(order.expiresAt());
    var completed = complete(order.id(), current);
    var stock = catalog.get(product.id());
    var events = outbox.findByOrderIdOrderByRevisionAsc(order.id());
    clock.set(order.expiresAt().plusSeconds(1));

    if (requested == OrderStatus.EXPIRED) {
      assertThat(service.expire(order.id())).isFalse();
    } else if (current == requested || current == OrderStatus.EXPIRED) {
      assertThat(complete(order.id(), requested)).isEqualTo(completed);
    } else {
      assertThatThrownBy(() -> complete(order.id(), requested))
          .isInstanceOfSatisfying(
              ApiException.class,
              error -> assertThat(error.code()).isEqualTo("INVALID_TRANSITION"));
    }

    assertThat(service.get("alice", order.id())).isEqualTo(completed);
    assertThat(orders.findById(order.id()).orElseThrow().revision()).isEqualTo(2);
    assertThat(catalog.get(product.id())).isEqualTo(stock);
    assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id()))
        .extracting(OutboxEvent::id, OutboxEvent::payload)
        .containsExactlyElementsOf(
            events.stream().map(event -> tuple(event.id(), event.payload())).toList());
  }

  static Stream<Arguments> terminalTransitions() {
    return Stream.of(OrderStatus.values())
        .filter(current -> current != OrderStatus.RESERVED)
        .flatMap(
            current ->
                Stream.of(OrderStatus.values())
                    .filter(requested -> requested != OrderStatus.RESERVED)
                    .map(requested -> Arguments.of(current, requested)));
  }

  @Test
  void rolledBackReservationLeavesItsIdempotencyKeyReusable() {
    var product = TestProducts.create(catalog, 2);
    var request = new ReserveRequest(product.id(), 2);
    var rolledBack =
        new TransactionTemplate(transactions)
            .execute(
                transaction -> {
                  var order = placement.place("alice", "retry-rollback", request);
                  assertThat(outbox.findByOrderIdOrderByRevisionAsc(order.id())).hasSize(1);
                  transaction.setRollbackOnly();
                  return order;
                });

    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
    assertThat(catalog.get(product.id())).isEqualTo(product);
    var retried = placement.place("alice", "retry-rollback", request);
    assertThat(retried.id()).isNotEqualTo(rolledBack.id());
    assertThat(placement.place("alice", "retry-rollback", request)).isEqualTo(retried);
    assertThat(orders.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
    assertThat(catalog.get(product.id()).reserved()).isEqualTo(2);
  }

  @Test
  void insufficientStockDoesNotConsumeTheKeyBeforeRestocking() {
    var product = TestProducts.create(catalog, 1);
    var request = new ReserveRequest(product.id(), 2);
    assertThatThrownBy(() -> placement.place("alice", "restock-retry", request))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_STOCK"));
    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
    assertThat(catalog.get(product.id())).isEqualTo(product);

    catalog.adjustStock(product.id(), 1);
    var order = placement.place("alice", "restock-retry", request);
    assertThat(placement.place("alice", "restock-retry", request)).isEqualTo(order);
    assertThat(orders.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
    var stock = catalog.get(product.id());
    assertThat(stock.available()).isZero();
    assertThat(stock.reserved()).isEqualTo(2);
    assertThat(stock.initialStock()).isEqualTo(2);
  }

  @Test
  void aLaterFailedReservationRollsBackEarlierWritesInTheSameTransaction() {
    var first = TestProducts.create(catalog, 2);
    var second = TestProducts.create(catalog, 0);
    var request = new ReserveRequest(first.id(), 2);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        transaction -> {
                          placement.place("alice", "first", request);
                          placement.place("alice", "second", new ReserveRequest(second.id(), 1));
                        }))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_STOCK"));
    assertThat(catalog.get(first.id())).isEqualTo(first);
    assertThat(catalog.get(second.id())).isEqualTo(second);
    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
    var retried = placement.place("alice", "first", request);
    assertThat(placement.place("alice", "first", request)).isEqualTo(retried);
    assertThat(orders.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
  }

  private OrderView complete(UUID id, OrderStatus target) {
    return switch (target) {
      case CONFIRMED -> service.payment(id, true);
      case PAYMENT_FAILED -> service.payment(id, false);
      case CANCELLED -> service.cancel("alice", id);
      case EXPIRED -> {
        assertThat(service.expire(id)).isTrue();
        yield service.get("alice", id);
      }
      default -> throw new AssertionError(target);
    };
  }
}
