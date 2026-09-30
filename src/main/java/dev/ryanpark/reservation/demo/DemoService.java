package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.inventory.ProductDtos.ProductView;
import dev.ryanpark.reservation.order.OrderDtos.OrderView;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderStatus;
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
@Profile("demo")
public class DemoService {
  public static final List<String> SCENARIOS =
      List.of("contention", "idempotency", "lifecycle", "race", "expiry");

  private final CatalogService catalog;
  private final OrderService orders;
  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public DemoService(CatalogService catalog, OrderService orders, JdbcTemplate jdbc, Clock clock) {
    this.catalog = catalog;
    this.orders = orders;
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
    try {
      return switch (scenario) {
        case "contention" -> contention(started);
        case "idempotency" -> idempotency(started);
        case "lifecycle" -> lifecycle(started);
        case "race" -> race(started);
        case "expiry" -> expiry(started);
        default -> throw ApiException.invalid("Unknown demo scenario");
      };
    } finally {
      running.set(false);
    }
  }

  private Result contention(long started) throws Exception {
    var product = product("contention", 25);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    steps.add(snapshot("Before requests", product));
    var calls =
        IntStream.range(0, 120)
            .<Callable<OrderView>>mapToObj(
                index ->
                    () ->
                        orders.reserve(
                            owner, "request-" + index, new ReserveRequest(product.id(), 1)))
            .toList();
    var attempts = parallel(16, calls);
    var after = snapshot("After 120 requests", product);
    steps.add(after);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("Exactly 25 reservations accepted", count(attempts, "RESERVED") == 25);
    checks.put(
        "Exactly 95 requests rejected for insufficient stock",
        count(attempts, "INSUFFICIENT_STOCK") == 95);
    checks.put("Exactly 25 order rows persisted", orderCount(product) == 25);
    checks.put("No overselling", stock(after, 0, 25, 0));
    return result(
        "contention",
        started,
        attempts,
        steps,
        checks,
        "120 service calls on 16 server worker threads, not 120 simultaneous connections. Each call uses the real transactional reservation service and database row lock.");
  }

  private Result idempotency(long started) throws Exception {
    var product = product("idempotency", 10);
    var owner = owner(product);
    var request = new ReserveRequest(product.id(), 3);
    var steps = new ArrayList<Snapshot>();
    steps.add(snapshot("Before retries", product));
    var calls =
        IntStream.range(0, 16)
            .<Callable<OrderView>>mapToObj(
                index -> () -> orders.reserve(owner, "same-key", request))
            .toList();
    var attempts = new ArrayList<>(parallel(16, calls));
    var after = snapshot("After 16 identical requests", product);
    steps.add(after);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("All 16 retries return a reservation", count(attempts, "RESERVED") == 16);
    checks.put(
        "All retries return the same order ID",
        attempts.stream().map(Attempt::orderId).distinct().count() == 1);
    checks.put("Stock reserved only once", stock(after, 7, 3, 0) && orderCount(product) == 1);
    var conflict =
        attempt(() -> orders.reserve(owner, "same-key", new ReserveRequest(product.id(), 4)));
    attempts.add(conflict);
    checks.put("Changed payload is rejected", conflict.code().equals("IDEMPOTENCY_CONFLICT"));
    var finalStock = snapshot("After rejected payload change", product);
    steps.add(finalStock);
    checks.put(
        "Rejected retry leaves stock unchanged",
        stock(finalStock, 7, 3, 0) && orderCount(product) == 1);
    return result(
        "idempotency",
        started,
        attempts,
        steps,
        checks,
        "Same owner, key and payload: one order. Reusing the key with a different quantity must fail.");
  }

  private Result lifecycle(long started) {
    var product = product("lifecycle", 12);
    var owner = owner(product);
    var steps = new ArrayList<Snapshot>();
    var checks = new LinkedHashMap<String, Boolean>();
    steps.add(snapshot("Initial stock", product));
    var paid = orders.reserve(owner, new ReserveRequest(product.id(), 3));
    steps.add(snapshot("Reserve 3 units", product));
    var confirmed = orders.payment(paid.id(), true);
    orders.payment(paid.id(), true);
    var afterPayment = snapshot("Confirm payment twice", product);
    steps.add(afterPayment);
    checks.put(
        "Repeated payment sells only once",
        confirmed.status() == OrderStatus.CONFIRMED && stock(afterPayment, 9, 0, 3));
    var cancelled = orders.reserve(owner, new ReserveRequest(product.id(), 2));
    steps.add(snapshot("Reserve 2 more units", product));
    orders.cancel(owner, cancelled.id());
    orders.cancel(owner, cancelled.id());
    var afterCancel = snapshot("Cancel twice", product);
    steps.add(afterCancel);
    checks.put(
        "Repeated cancellation restores only once",
        orders.get(owner, cancelled.id()).status() == OrderStatus.CANCELLED
            && stock(afterCancel, 9, 0, 3));
    var failed = orders.reserve(owner, new ReserveRequest(product.id(), 2));
    steps.add(snapshot("Reserve 2 more units", product));
    orders.payment(failed.id(), false);
    orders.payment(failed.id(), false);
    var afterFailure = snapshot("Fail payment twice", product);
    steps.add(afterFailure);
    checks.put(
        "Repeated payment failure restores only once",
        orders.get(owner, failed.id()).status() == OrderStatus.PAYMENT_FAILED
            && stock(afterFailure, 9, 0, 3));
    checks.put("Exactly three orders persisted", orderCount(product) == 3);
    return result(
        "lifecycle",
        started,
        List.of(),
        steps,
        checks,
        "Payment is simulated; no money is charged. Every inventory snapshot is read back from the database.");
  }

  private Result race(long started) throws Exception {
    var product = product("race", 1);
    var owner = owner(product);
    var reservation = orders.reserve(owner, new ReserveRequest(product.id(), 1));
    var before = snapshot("One reserved unit", product);
    var attempts =
        parallel(
            2,
            List.of(
                () -> orders.payment(reservation.id(), true),
                () -> orders.cancel(owner, reservation.id())));
    var after = snapshot("After payment / cancellation race", product);
    var finalOrder = orders.get(owner, reservation.id());
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
        List.of(before, after),
        checks,
        "Two worker threads race to confirm and cancel the same order. Either outcome is valid, but inventory must change only once.");
  }

  private Result expiry(long started) {
    var product = product("expiry", 5);
    var owner = owner(product);
    var reservation = orders.reserve(owner, new ReserveRequest(product.id(), 2));
    var before = snapshot("Two units reserved", product);
    var checks = new LinkedHashMap<String, Boolean>();
    checks.put("An unexpired order is not expired early", !orders.expire(reservation.id()));
    int updated =
        jdbc.update(
            "UPDATE reservations SET expires_at = ? WHERE id = ? AND owner_id = ? AND status = 'RESERVED'",
            Timestamp.from(clock.instant().minusSeconds(1)),
            reservation.id(),
            owner);
    orders.expire(reservation.id());
    checks.put("Only this generated order's deadline is advanced", updated == 1);
    checks.put(
        "Order reaches EXPIRED",
        orders.get(owner, reservation.id()).status() == OrderStatus.EXPIRED);
    checks.put("Repeating expiry is a no-op", !orders.expire(reservation.id()));
    var after = snapshot("After expiry and retry", product);
    checks.put("All reserved units returned exactly once", stock(after, 5, 0, 0));
    return result(
        "expiry",
        started,
        List.of(),
        List.of(before, after),
        checks,
        "Fast-forward fixture: only this demo order's deadline is moved into the past, then the normal expiry service runs. This does not wait for the two-minute scheduler or change other orders.");
  }

  private ProductView product(String name, int stock) {
    return catalog.create(
        new CreateProduct(
            "DEMO-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            "Demo: " + name,
            2500,
            "CAD",
            stock));
  }

  private String owner(ProductView product) {
    return "demo-" + product.id();
  }

  private long orderCount(ProductView product) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, product.id());
  }

  private Snapshot snapshot(String label, ProductView product) {
    return new Snapshot(label, catalog.get(product.id()));
  }

  private boolean stock(Snapshot snapshot, int available, int reserved, int sold) {
    var p = snapshot.inventory();
    return p.available() == available && p.reserved() == reserved && p.sold() == sold;
  }

  private long count(List<Attempt> attempts, String code) {
    return attempts.stream().filter(attempt -> attempt.code().equals(code)).count();
  }

  private Attempt attempt(Callable<OrderView> call) throws Exception {
    try {
      var order = call.call();
      return new Attempt(order.status().name(), order.id());
    } catch (ApiException ex) {
      if (ex.status().value() != 409) throw ex;
      return new Attempt(ex.code(), null);
    }
  }

  private List<Attempt> parallel(int workers, List<Callable<OrderView>> calls) throws Exception {
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
                    return attempt(call);
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
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        outcomes,
        List.copyOf(steps),
        checks,
        note);
  }

  public record Snapshot(String label, ProductView inventory) {}

  private record Attempt(String code, UUID orderId) {}

  public record Result(
      String scenario,
      boolean passed,
      long durationMs,
      Map<String, Long> outcomes,
      List<Snapshot> snapshots,
      Map<String, Boolean> checks,
      String note) {}
}
