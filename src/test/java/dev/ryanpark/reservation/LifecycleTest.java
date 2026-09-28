package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.*;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.*;
import dev.ryanpark.reservation.order.OrderDtos.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LifecycleTest {
  @Autowired CatalogService catalog;
  @Autowired OrderService service;
  @Autowired OrderPlacement placement;
  @Autowired ReservationRepository orders;
  @Autowired ReservationExpiry expiry;
  @Autowired JdbcTemplate jdbc;

  private UUID product(int stock) {
    return catalog
        .create(
            new CreateProduct(
                "LIFE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Lifecycle product",
                900,
                "USD",
                stock))
        .id();
  }

  private void pastDeadline(UUID id) {
    jdbc.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(10)),
        id);
  }

  @Test
  void concurrentRetriesCreateOneReservation() throws Exception {
    var product = product(10);
    var key = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    var ids = new HashSet<UUID>();
    try (var pool = Executors.newFixedThreadPool(12)) {
      var futures = new ArrayList<Future<OrderView>>();
      for (int i = 0; i < 30; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return placement.place("alice", key, new ReserveRequest(product, 2));
                }));
      start.countDown();
      for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS).id());
    }
    assertThat(ids).hasSize(1);
    assertThat(catalog.get(product).available()).isEqualTo(8);
    assertThat(orders.countByProductId(product)).isEqualTo(1);
  }

  @Test
  void keyIsScopedToOwnerAndPayload() {
    var product = product(10);
    var key = UUID.randomUUID().toString();
    var first = placement.place("alice", key, new ReserveRequest(product, 1));
    assertThat(placement.place("alice", key, new ReserveRequest(product, 1)).id())
        .isEqualTo(first.id());
    assertThat(placement.place("bob", key, new ReserveRequest(product, 1)).id())
        .isNotEqualTo(first.id());
    assertThatThrownBy(() -> placement.place("alice", key, new ReserveRequest(product, 2)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
  }

  @Test
  void sameKeyRacingAcrossProductsRollsBackLosingInventory() throws Exception {
    var a = product(5);
    var b = product(5);
    var key = UUID.randomUUID().toString();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<Future<Boolean>>();
      for (var id : List.of(a, b))
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    placement.place("alice", key, new ReserveRequest(id, 1));
                    return true;
                  } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    return false;
                  }
                }));
      start.countDown();
      int successes = 0;
      for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
    }
    assertThat(catalog.get(a).available() + catalog.get(b).available()).isEqualTo(9);
    assertThat(orders.countByProductId(a) + orders.countByProductId(b)).isEqualTo(1);
  }

  @Test
  void repeatedConfirmationSellsOnlyOnce() {
    var product = product(5);
    var order = service.reserve("alice", new ReserveRequest(product, 2));
    assertThat(service.payment(order.id(), true).status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(service.payment(order.id(), true).status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(catalog.get(product).sold()).isEqualTo(2);
    assertThat(catalog.get(product).reserved()).isZero();
    assertThatThrownBy(() -> service.cancel("alice", order.id())).isInstanceOf(ApiException.class);
  }

  @Test
  void cancellationAndPaymentFailureReleaseOnce() {
    var product = product(5);
    var a = service.reserve("alice", new ReserveRequest(product, 2));
    service.cancel("alice", a.id());
    service.cancel("alice", a.id());
    var b = service.reserve("bob", new ReserveRequest(product, 3));
    service.payment(b.id(), false);
    service.payment(b.id(), false);
    assertThat(catalog.get(product).available()).isEqualTo(5);
    assertThat(catalog.get(product).reserved()).isZero();
    assertThat(service.get("bob", b.id()).status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
  }

  @Test
  void latePaymentExpiresReservationEvenBeforeSweeperRuns() {
    var product = product(4);
    var order = service.reserve("alice", new ReserveRequest(product, 4));
    pastDeadline(order.id());
    assertThat(service.payment(order.id(), true).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(service.payment(order.id(), true).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(catalog.get(product).available()).isEqualTo(4);
    assertThat(catalog.get(product).sold()).isZero();
  }

  @Test
  void sweeperOnlyExpiresDueReservations() {
    var product = product(3);
    var due = service.reserve("alice", new ReserveRequest(product, 1));
    var future = service.reserve("alice", new ReserveRequest(product, 1));
    pastDeadline(due.id());
    expiry.expireDue();
    expiry.expireDue();
    assertThat(service.get("alice", due.id()).status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(service.get("alice", future.id()).status()).isEqualTo(OrderStatus.RESERVED);
    assertThat(catalog.get(product).available()).isEqualTo(2);
  }

  @Test
  void competingPaymentAndCancellationPreserveInventory() throws Exception {
    var product = product(20);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 20; i++) {
        var id = service.reserve("alice", new ReserveRequest(product, 1)).id();
        futures.add(pool.submit(() -> attempt(() -> service.payment(id, true))));
        futures.add(pool.submit(() -> attempt(() -> service.cancel("alice", id))));
      }
      for (var future : futures) future.get(30, TimeUnit.SECONDS);
    }
    var inventory = catalog.get(product);
    assertThat(inventory.reserved()).isZero();
    assertThat(inventory.available() + inventory.sold()).isEqualTo(20);
  }

  @Test
  void competingExpiryAndPaymentCannotSellExpiredStock() throws Exception {
    var product = product(1);
    var order = service.reserve("alice", new ReserveRequest(product, 1));
    pastDeadline(order.id());
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> service.expire(order.id()));
      var b = pool.submit(() -> service.payment(order.id(), true));
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
    }
    assertThat(catalog.get(product).available()).isEqualTo(1);
    assertThat(service.get("alice", order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
  }

  @Test
  void invalidKeyIsRejectedBeforeWriting() {
    var product = product(1);
    assertThatThrownBy(
            () -> placement.place("alice", "invalid key", new ReserveRequest(product, 1)))
        .isInstanceOf(ApiException.class);
    assertThat(catalog.get(product).available()).isEqualTo(1);
  }

  private void attempt(Runnable action) {
    try {
      action.run();
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo("INVALID_TRANSITION");
    }
  }
}
