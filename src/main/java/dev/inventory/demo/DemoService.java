package dev.inventory.demo;

import dev.inventory.common.ApiException;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile({"demo", "public-demo"})
public class DemoService {
  public static final List<String> SCENARIOS =
      List.of("contention", "idempotency", "lifecycle", "race", "expiry");

  private final Clock clock;
  private final ConcurrentDemoScenarios concurrent;
  private final LifecycleDemoScenarios lifecycle;
  private final AtomicBoolean running = new AtomicBoolean();

  public DemoService(
      CatalogService catalog,
      OrderService orders,
      OrderPlacement placement,
      JdbcTemplate jdbc,
      Clock clock) {
    this.clock = clock;
    var results = new DemoResults(catalog, jdbc, clock);
    this.concurrent = new ConcurrentDemoScenarios(orders, placement, results);
    this.lifecycle = new LifecycleDemoScenarios(orders, placement, results, jdbc, clock);
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
        case "contention" -> concurrent.contention(started, log);
        case "idempotency" -> concurrent.idempotency(started, log);
        case "lifecycle" -> lifecycle.lifecycle(started, log);
        case "race" -> concurrent.race(started, log);
        case "expiry" -> lifecycle.expiry(started, log);
        default -> throw ApiException.invalid("Unknown demo scenario");
      };
    } finally {
      running.set(false);
    }
  }

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
