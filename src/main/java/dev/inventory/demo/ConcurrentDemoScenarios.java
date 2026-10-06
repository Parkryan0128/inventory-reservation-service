package dev.inventory.demo;

import static dev.inventory.demo.DemoRequests.attempt;
import static dev.inventory.demo.DemoRequests.parallel;
import static dev.inventory.demo.DemoRequests.requestId;
import static dev.inventory.demo.DemoResults.*;

import dev.inventory.demo.DemoRequests.Work;
import dev.inventory.demo.DemoService.Attempt;
import dev.inventory.demo.DemoService.Result;
import dev.inventory.demo.DemoService.Snapshot;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

final class ConcurrentDemoScenarios {
  private final OrderService orders;
  private final OrderPlacement placement;
  private final DemoResults results;

  ConcurrentDemoScenarios(OrderService orders, OrderPlacement placement, DemoResults results) {
    this.orders = orders;
    this.placement = placement;
    this.results = results;
  }

  Result contention(long started, DemoActivityLog log) throws Exception {
    var product = results.product(5);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    results.snapshot("Before requests", product, steps, log);
    var calls =
        IntStream.range(0, 100)
            .mapToObj(
                index ->
                    new Work(
                        requestId(index),
                        "customer-" + String.format(Locale.ROOT, "%03d", index + 1),
                        "reserve",
                        1,
                        "request-" + index,
                        () ->
                            placement.place(
                                owner + "-" + index,
                                "request-" + index,
                                new ReserveRequest(product.id(), 1))))
            .toList();
    var attempts = parallel(16, calls, log);
    var after = results.snapshot("After 100 requests", product, steps, log);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("Exactly 5 reservations accepted", count(attempts, "RESERVED") == 5);
    checks.put(
        "Exactly 95 requests rejected for insufficient stock",
        count(attempts, "INSUFFICIENT_STOCK") == 95);
    checks.put("Exactly 5 order rows persisted", results.orderCount(product) == 5);
    checks.put("No overselling", stock(after, 0, 5, 0));
    return results.result(
        "contention",
        started,
        attempts,
        steps,
        checks,
        log,
        "100 reservation service calls, 100 generated customers, 16 server workers. Workers record"
            + " results after the transactional service returns. Log order is observation order,"
            + " not guaranteed database commit order. These are not 100 HTTP connections.");
  }

  Result idempotency(long started, DemoActivityLog log) throws Exception {
    var product = results.product(10);
    var owner = owner(product);
    var request = new ReserveRequest(product.id(), 3);
    var steps = new ArrayList<Snapshot>();
    results.snapshot("Before retries", product, steps, log);
    var calls =
        IntStream.range(0, 16)
            .mapToObj(
                index ->
                    new Work(
                        requestId(index),
                        "customer-001",
                        "reserve",
                        3,
                        "same-key",
                        () -> placement.place(owner, "same-key", request)))
            .toList();
    var attempts = new ArrayList<>(parallel(16, calls, log));
    var after = results.snapshot("After 16 identical requests", product, steps, log);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("All 16 retries return a reservation", count(attempts, "RESERVED") == 16);
    checks.put(
        "All retries return the same order ID",
        attempts.stream().map(Attempt::orderId).distinct().count() == 1);
    checks.put(
        "Stock reserved only once", stock(after, 7, 3, 0) && results.orderCount(product) == 1);
    var conflict =
        attempt(
            new Work(
                requestId(16),
                "customer-001",
                "reserve",
                4,
                "same-key",
                () -> placement.place(owner, "same-key", new ReserveRequest(product.id(), 4))),
            log);
    attempts.add(conflict);
    checks.put("Changed payload is rejected", conflict.code().equals("IDEMPOTENCY_CONFLICT"));
    var finalStock = results.snapshot("After rejected payload change", product, steps, log);
    checks.put(
        "Rejected retry leaves stock unchanged",
        stock(finalStock, 7, 3, 0) && results.orderCount(product) == 1);
    return results.result(
        "idempotency",
        started,
        attempts,
        steps,
        checks,
        log,
        "16 identical calls use the same owner, key and quantity. One final call reuses the key"
            + " with quantity 4. A returned order ID can appear many times without creating"
            + " additional orders.");
  }

  Result race(long started, DemoActivityLog log) throws Exception {
    var product = results.product(1);
    var owner = owner(product);
    var reservation =
        attempt(
            new Work(
                "setup",
                "customer-001",
                "reserve",
                1,
                "race",
                () -> placement.place(owner, "race", new ReserveRequest(product.id(), 1))),
            log);
    var steps = new ArrayList<Snapshot>();
    results.snapshot("One reserved unit", product, steps, log);
    var attempts =
        parallel(
            2,
            List.of(
                new Work(
                    requestId(0),
                    "payment",
                    "payment-success",
                    1,
                    "",
                    () -> orders.payment(reservation.orderId(), true)),
                new Work(
                    requestId(1),
                    "customer-001",
                    "cancel",
                    1,
                    "",
                    () -> orders.cancel(owner, reservation.orderId()))),
            log);
    var after = results.snapshot("After payment / cancellation race", product, steps, log);
    var finalOrder = orders.get(owner, reservation.orderId());
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put(
        "Exactly one transition wins",
        count(attempts, "CONFIRMED") + count(attempts, "CANCELLED") == 1);
    checks.put("Losing transition is rejected", count(attempts, "INVALID_TRANSITION") == 1);
    checks.put(
        "Stock matches the winning transition",
        (finalOrder.status() == OrderStatus.CONFIRMED && stock(after, 0, 0, 1))
            || (finalOrder.status() == OrderStatus.CANCELLED && stock(after, 1, 0, 0)));
    return results.result(
        "race",
        started,
        attempts,
        steps,
        checks,
        log,
        "Setup reserves one unit. Two worker threads then call payment and cancellation against"
            + " that same order. Either outcome is valid. Activity is recorded after each service"
            + " call returns; the UI does not invent lock-acquisition events.");
  }
}
