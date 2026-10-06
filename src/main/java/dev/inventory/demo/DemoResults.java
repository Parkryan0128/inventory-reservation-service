package dev.inventory.demo;

import static dev.inventory.demo.DemoRequests.elapsed;

import dev.inventory.demo.DemoService.Attempt;
import dev.inventory.demo.DemoService.Result;
import dev.inventory.demo.DemoService.Snapshot;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.inventory.ProductDtos.ProductView;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

final class DemoResults {
  private final CatalogService catalog;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  DemoResults(CatalogService catalog, JdbcTemplate jdbc, Clock clock) {
    this.catalog = catalog;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  ProductView product(int stock) {
    return catalog.create(
        new CreateProduct(
            "DEMO-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            "Vector One",
            89900,
            "CAD",
            stock));
  }

  static String owner(ProductView product) {
    return "demo-" + product.id();
  }

  long orderCount(ProductView product) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, product.id());
  }

  Snapshot snapshot(String label, ProductView product, List<Snapshot> steps, DemoActivityLog log) {
    var step = new Snapshot(label, catalog.get(product.id()));
    steps.add(step);
    log.snapshot(steps.size() - 1);
    return step;
  }

  static boolean stock(Snapshot snapshot, int available, int reserved, int sold) {
    var p = snapshot.inventory();
    return p.available() == available && p.reserved() == reserved && p.sold() == sold;
  }

  static boolean returnedTwice(UUID orderId, String code, Attempt first, Attempt repeated) {
    return code.equals(first.code())
        && code.equals(repeated.code())
        && orderId.equals(first.orderId())
        && orderId.equals(repeated.orderId());
  }

  static long count(List<Attempt> attempts, String code) {
    return attempts.stream().filter(attempt -> attempt.code().equals(code)).count();
  }

  Result result(
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
}
