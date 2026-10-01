package dev.inventory.demo;

import dev.inventory.common.ApiException;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile({"demo", "public-demo"})
public class DemoService {
  public static final List<String> SCENARIOS =
      List.of("contention", "idempotency", "lifecycle", "race", "expiry");

  private final CatalogService catalog;
  private final OrderService orders;
  private final OrderPlacement placement;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public DemoService(
      CatalogService catalog,
      OrderService orders,
      OrderPlacement placement,
      JdbcTemplate jdbc,
      Clock clock) {
    this.catalog = catalog;
    this.orders = orders;
    this.placement = placement;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public boolean busy() {
    return running.get();
  }

  public Result run(String scenario) throws Exception {
    if (!SCENARIOS.contains(scenario)) throw ApiException.invalid("Unknown demo scenario");
    if (!running.compareAndSet(false, true))
      throw ApiException.conflict("DEMO_BUSY", "Another demo is running. Try again shortly.");
    long started = System.nanoTime();
    var log = new DemoActivityLog(clock);
    try {
      return switch (scenario) {
        case "contention" -> contention(started, log);
        case "idempotency" -> idempotency(started, log);
        case "lifecycle" -> lifecycle(started, log);
        case "race" -> race(started, log);
        case "expiry" -> expiry(started, log);
        default -> throw ApiException.invalid("Unknown demo scenario");
      };
    } finally {
      running.set(false);
    }
  }

  private Result contention(long started, DemoActivityLog log) throws Exception {
    var product = product(5);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    snapshot("Before requests", product, steps, log);
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
    var after = snapshot("After 100 requests", product, steps, log);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("Exactly 5 reservations accepted", count(attempts, "RESERVED") == 5);
    checks.put(
        "Exactly 95 requests rejected for insufficient stock",
        count(attempts, "INSUFFICIENT_STOCK") == 95);
    checks.put("Exactly 5 order rows persisted", orderCount(product) == 5);
    checks.put("No overselling", stock(after, 0, 5, 0));
    return result(
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

  private Result idempotency(long started, DemoActivityLog log) throws Exception {
    var product = product(10);
    var owner = owner(product);
    var request = new ReserveRequest(product.id(), 3);
    var steps = new ArrayList<Snapshot>();
    snapshot("Before retries", product, steps, log);
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
    var after = snapshot("After 16 identical requests", product, steps, log);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("All 16 retries return a reservation", count(attempts, "RESERVED") == 16);
    checks.put(
        "All retries return the same order ID",
        attempts.stream().map(Attempt::orderId).distinct().count() == 1);
    checks.put("Stock reserved only once", stock(after, 7, 3, 0) && orderCount(product) == 1);
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
    var finalStock = snapshot("After rejected payload change", product, steps, log);
    checks.put(
        "Rejected retry leaves stock unchanged",
        stock(finalStock, 7, 3, 0) && orderCount(product) == 1);
    return result(
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

  private Result lifecycle(long started, DemoActivityLog log) throws Exception {
    var product = product(12);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    var checks = new LinkedHashMap<String, Boolean>();
    snapshot("Initial stock", product, steps, log);
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
    snapshot("Reserve 3 units", product, steps, log);
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
    var afterPayment = snapshot("Confirm payment twice", product, steps, log);
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
    snapshot("Reserve 2 more units", product, steps, log);
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
    var afterCancel = snapshot("Cancel twice", product, steps, log);
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
    snapshot("Reserve 2 more units", product, steps, log);
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
    var afterFailure = snapshot("Fail payment twice", product, steps, log);
    checks.put(
        "Repeated payment failure restores only once",
        returnedTwice(failed.orderId(), "PAYMENT_FAILED", paymentFailure, paymentFailureAgain)
            && orders.get(owner, failed.orderId()).status() == OrderStatus.PAYMENT_FAILED
            && stock(afterFailure, 9, 0, 3));
    checks.put("Exactly three orders persisted", orderCount(product) == 3);
    return result(
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

  private Result race(long started, DemoActivityLog log) throws Exception {
    var product = product(1);
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
    snapshot("One reserved unit", product, steps, log);
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
    var after = snapshot("After payment / cancellation race", product, steps, log);
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
    return result(
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

  private Result expiry(long started, DemoActivityLog log) throws Exception {
    var product = product(5);
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
    snapshot("Two units reserved", product, steps, log);
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
    var after = snapshot("After expiry and retry", product, steps, log);
    checks.put("All reserved units returned exactly once", stock(after, 5, 0, 0));
    return result(
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

  private ProductView product(int stock) {
    return catalog.create(
        new CreateProduct(
            "DEMO-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            "Vector One",
            89900,
            "CAD",
            stock));
  }

  private String owner(ProductView product) {
    return "demo-" + product.id();
  }

  private static String requestId(int index) {
    return String.format(Locale.ROOT, "req-%03d", index + 1);
  }

  private long orderCount(ProductView product) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, product.id());
  }

  private Snapshot snapshot(
      String label, ProductView product, List<Snapshot> steps, DemoActivityLog log) {
    var step = new Snapshot(label, catalog.get(product.id()));
    steps.add(step);
    log.snapshot(steps.size() - 1);
    return step;
  }

  private boolean stock(Snapshot snapshot, int available, int reserved, int sold) {
    var p = snapshot.inventory();
    return p.available() == available && p.reserved() == reserved && p.sold() == sold;
  }

  private boolean returnedTwice(UUID orderId, String code, Attempt first, Attempt repeated) {
    return code.equals(first.code())
        && code.equals(repeated.code())
        && orderId.equals(first.orderId())
        && orderId.equals(repeated.orderId());
  }

  private long count(List<Attempt> attempts, String code) {
    return attempts.stream().filter(attempt -> attempt.code().equals(code)).count();
  }

  private static long elapsed(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  private Attempt attempt(Work work, DemoActivityLog log) throws Exception {
    long started = System.nanoTime();
    Attempt result;
    try {
      var order = work.call().call();
      result = new Attempt(order.status().name(), order.id(), elapsed(started));
    } catch (ApiException ex) {
      if (ex.status().value() != 409) throw ex;
      result = new Attempt(ex.code(), null, elapsed(started));
    }
    log.record(
        work.requestId(),
        work.actor(),
        work.operation(),
        result.code(),
        result.orderId(),
        work.quantity(),
        work.key(),
        result.durationMs());
    return result;
  }

  private List<Attempt> parallel(int workers, List<Work> calls, DemoActivityLog log)
      throws Exception {
    var ready = new CountDownLatch(Math.min(workers, calls.size()));
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<Attempt>>();
    try (var pool = Executors.newFixedThreadPool(workers)) {
      try {
        for (var call : calls) {
          futures.add(
              pool.submit(
                  () -> {
                    ready.countDown();
                    start.await();
                    return attempt(call, log);
                  }));
        }
        if (!ready.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Demo workers did not start");
        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        var results = new ArrayList<Attempt>();
        for (var future : futures) {
          results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        }
        return results;
      } finally {
        start.countDown();
        for (var future : futures) if (!future.isDone()) future.cancel(true);
      }
    }
  }

  private Result result(
      String scenario,
      long started,
      List<Attempt> attempts,
      List<Snapshot> steps,
      LinkedHashMap<String, Boolean> checks,
      DemoActivityLog log,
      String note) {
    checks.put(
        "Stock stays non-negative and balanced at every snapshot",
        steps.stream()
            .allMatch(
                step -> {
                  var p = step.inventory();
                  return p.available() >= 0
                      && p.reserved() >= 0
                      && p.sold() >= 0
                      && p.available() + p.reserved() + p.sold() == p.initialStock();
                }));
    var outcomes = new LinkedHashMap<String, Long>();
    for (var attempt : attempts) outcomes.merge(attempt.code(), 1L, Long::sum);
    return new Result(
        scenario,
        checks.values().stream().allMatch(Boolean::booleanValue),
        elapsed(started),
        outcomes,
        List.copyOf(steps),
        checks,
        note,
        List.copyOf(attempts),
        orderCount(steps.getLast().inventory()),
        clock.instant().toString(),
        log.entries());
  }

  private record Work(
      String requestId,
      String actor,
      String operation,
      int quantity,
      String key,
      Callable<OrderView> call) {}

  public record Snapshot(String label, ProductView inventory) {}

  public record Attempt(String code, UUID orderId, long durationMs) {}

  public record Result(
      String scenario,
      boolean passed,
      long durationMs,
      Map<String, Long> outcomes,
      List<Snapshot> snapshots,
      Map<String, Boolean> checks,
      String note,
      List<Attempt> attempts,
      long persistedOrders,
      String completedAt,
      List<DemoActivityLog.Entry> activity) {}
}
