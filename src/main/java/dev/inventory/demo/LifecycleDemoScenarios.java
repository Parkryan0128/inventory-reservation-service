package dev.inventory.demo;

import static dev.inventory.demo.DemoRequests.attempt;
import static dev.inventory.demo.DemoRequests.elapsed;
import static dev.inventory.demo.DemoRequests.requestId;
import static dev.inventory.demo.DemoResults.*;

import dev.inventory.demo.DemoRequests.Work;
import dev.inventory.demo.DemoService.Result;
import dev.inventory.demo.DemoService.Snapshot;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class LifecycleDemoScenarios {
  private final OrderService orders;
  private final OrderPlacement placement;
  private final DemoResults results;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  LifecycleDemoScenarios(
      OrderService orders,
      OrderPlacement placement,
      DemoResults results,
      JdbcTemplate jdbc,
      Clock clock) {
    this.orders = orders;
    this.placement = placement;
    this.results = results;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  Result lifecycle(long started, DemoActivityLog log) throws Exception {
    var product = results.product(12);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    var checks = new LinkedHashMap<String, Boolean>();
    results.snapshot("Initial stock", product, steps, log);
    var paid =
        attempt(
            new Work(
                requestId(0),
                "customer-001",
                "reserve",
                3,
                "purchase",
                () -> placement.place(owner, "purchase", new ReserveRequest(product.id(), 3))),
            log);
    results.snapshot("Reserve 3 units", product, steps, log);
    var confirmed =
        attempt(
            new Work(
                requestId(1),
                "payment",
                "payment-success",
                3,
                "",
                () -> orders.payment(paid.orderId(), true)),
            log);
    var confirmedAgain =
        attempt(
            new Work(
                requestId(2),
                "payment",
                "payment-success",
                3,
                "",
                () -> orders.payment(paid.orderId(), true)),
            log);
    var afterPayment = results.snapshot("Confirm payment twice", product, steps, log);
    checks.put(
        "Repeated payment sells only once",
        returnedTwice(paid.orderId(), "CONFIRMED", confirmed, confirmedAgain)
            && stock(afterPayment, 9, 0, 3));
    var cancelled =
        attempt(
            new Work(
                requestId(3),
                "customer-001",
                "reserve",
                2,
                "cancel",
                () -> placement.place(owner, "cancel", new ReserveRequest(product.id(), 2))),
            log);
    results.snapshot("Reserve 2 more units", product, steps, log);
    var cancellation =
        attempt(
            new Work(
                requestId(4),
                "customer-001",
                "cancel",
                2,
                "",
                () -> orders.cancel(owner, cancelled.orderId())),
            log);
    var cancellationAgain =
        attempt(
            new Work(
                requestId(5),
                "customer-001",
                "cancel",
                2,
                "",
                () -> orders.cancel(owner, cancelled.orderId())),
            log);
    var afterCancel = results.snapshot("Cancel twice", product, steps, log);
    checks.put(
        "Repeated cancellation restores only once",
        returnedTwice(cancelled.orderId(), "CANCELLED", cancellation, cancellationAgain)
            && orders.get(owner, cancelled.orderId()).status() == OrderStatus.CANCELLED
            && stock(afterCancel, 9, 0, 3));
    var failed =
        attempt(
            new Work(
                requestId(6),
                "customer-001",
                "reserve",
                2,
                "failure",
                () -> placement.place(owner, "failure", new ReserveRequest(product.id(), 2))),
            log);
    results.snapshot("Reserve 2 more units", product, steps, log);
    var paymentFailure =
        attempt(
            new Work(
                requestId(7),
                "payment",
                "payment-failure",
                2,
                "",
                () -> orders.payment(failed.orderId(), false)),
            log);
    var paymentFailureAgain =
        attempt(
            new Work(
                requestId(8),
                "payment",
                "payment-failure",
                2,
                "",
                () -> orders.payment(failed.orderId(), false)),
            log);
    var afterFailure = results.snapshot("Fail payment twice", product, steps, log);
    checks.put(
        "Repeated payment failure restores only once",
        returnedTwice(failed.orderId(), "PAYMENT_FAILED", paymentFailure, paymentFailureAgain)
            && orders.get(owner, failed.orderId()).status() == OrderStatus.PAYMENT_FAILED
            && stock(afterFailure, 9, 0, 3));
    checks.put("Exactly three orders persisted", results.orderCount(product) == 3);
    return results.result(
        "lifecycle",
        started,
        List.of(),
        steps,
        checks,
        log,
        "Three orders use one isolated demo owner. Payment success, cancellation and payment"
            + " failure are each called twice. No real payment is made. Stock snapshots are read"
            + " from the database.");
  }

  Result expiry(long started, DemoActivityLog log) throws Exception {
    var product = results.product(5);
    var owner = owner(product);
    var reservation =
        attempt(
            new Work(
                "setup",
                "customer-001",
                "reserve",
                2,
                "expiry",
                () -> placement.place(owner, "expiry", new ReserveRequest(product.id(), 2))),
            log);
    var steps = new ArrayList<Snapshot>();
    results.snapshot("Two units reserved", product, steps, log);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put(
        "An unexpired order is not expired early",
        !expire("expiry-001", reservation.orderId(), log));
    long fixtureStart = System.nanoTime();
    int updated =
        jdbc.update(
            "UPDATE reservations SET expires_at = ? WHERE id = ? AND owner_id = ? AND status ="
                + " 'RESERVED'",
            Timestamp.from(clock.instant().minusSeconds(1)),
            reservation.orderId(),
            owner);
    log.record(
        "fixture",
        "demo",
        "advance-deadline",
        updated == 1 ? "DEADLINE_ADVANCED" : "NO_CHANGE",
        reservation.orderId(),
        0,
        "",
        elapsed(fixtureStart));
    expire("expiry-002", reservation.orderId(), log);
    checks.put("Only this generated order's deadline is advanced", updated == 1);
    checks.put(
        "Order reaches EXPIRED",
        orders.get(owner, reservation.orderId()).status() == OrderStatus.EXPIRED);
    checks.put("Repeating expiry is a no-op", !expire("expiry-003", reservation.orderId(), log));
    var after = results.snapshot("After expiry and retry", product, steps, log);
    checks.put("All reserved units returned exactly once", stock(after, 5, 0, 0));
    return results.result(
        "expiry",
        started,
        List.of(),
        steps,
        checks,
        log,
        "The demo advances only its generated order's deadline, then invokes the normal expiry"
            + " service. This skips the two-minute wait; it is not a live scheduler trace."
            + " NO_CHANGE is the expiry service returning false.");
  }

  private boolean expire(String requestId, UUID id, DemoActivityLog log) {
    long started = System.nanoTime();
    boolean expired = orders.expire(id);
    log.record(
        requestId,
        "expiry-service",
        "expire",
        expired ? "EXPIRED" : "NO_CHANGE",
        id,
        2,
        "",
        elapsed(started));
    return expired;
  }
}
