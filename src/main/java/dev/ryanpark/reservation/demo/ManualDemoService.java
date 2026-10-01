package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.ProductView;
import dev.ryanpark.reservation.order.OrderDtos.OrderView;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile({"demo", "public-demo"})
public class ManualDemoService {
  private final CatalogService catalog;
  private final OrderService orders;
  private final Clock clock;
  private final SharedDemoProduct product;
  private final ManualActivityLog activity;
  private final long actionIntervalMs;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  public ManualDemoService(
      CatalogService catalog,
      OrderService orders,
      Clock clock,
      SharedDemoProduct product,
      ManualActivityLog activity,
      @Value("${app.manual-action-interval-ms:500}") long actionIntervalMs,
      PlatformTransactionManager transactions) {
    this.catalog = catalog;
    this.orders = orders;
    this.clock = clock;
    this.product = product;
    this.activity = activity;
    if (actionIntervalMs < 0 || actionIntervalMs > 60_000)
      throw new IllegalArgumentException("Manual action interval must be between 0 and 60000 ms");
    this.actionIntervalMs = actionIntervalMs;
    writes = new TransactionTemplate(transactions);
    reads = new TransactionTemplate(transactions);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public Workspace create() {
    return new Workspace(product.id());
  }

  public State state(Workspace workspace) {
    synchronized (workspace) {
      var snapshot = reads.execute(status -> snapshot(workspace, workspace.orderId));
      return view(workspace, snapshot, null);
    }
  }

  public Outcome act(Workspace workspace, Command command) {
    if (command == null
        || command.action() == null
        || command.quantity() < 1
        || command.quantity() > 10) {
      throw ApiException.invalid("An action and quantity between 1 and 10 are required");
    }
    synchronized (workspace) {
      if (clock.instant().isBefore(workspace.nextActionAt)) {
        var snapshot = reads.execute(status -> snapshot(workspace, workspace.orderId));
        return new Outcome(
            429,
            view(
                workspace,
                snapshot,
                new Result("RATE_LIMITED", "rejected", "Wait briefly before the next action", 0)));
      }
      workspace.nextActionAt = clock.instant().plusMillis(actionIntervalMs);
      long started = System.nanoTime();
      Snapshot snapshot;
      String code;
      String level = "ok";
      String message = "Request committed";
      int statusCode = 200;
      int quantity = command.quantity();
      try {
        snapshot =
            writes.execute(
                status -> {
                  UUID orderId = workspace.orderId;
                  switch (command.action()) {
                    case ADD_STOCK -> catalog.adjustStock(workspace.productId, command.quantity());
                    case REMOVE_STOCK ->
                        catalog.adjustStock(workspace.productId, -command.quantity());
                    case BUY -> {
                      if (orderId != null
                          && orders.get(workspace.owner, orderId).status()
                              == OrderStatus.RESERVED) {
                        throw ApiException.conflict(
                            "ACTIVE_RESERVATION", "Pay or cancel the current reservation first");
                      }
                      orderId =
                          orders
                              .reserve(
                                  workspace.owner,
                                  new ReserveRequest(workspace.productId, command.quantity()))
                              .id();
                    }
                    case PAY, CANCEL -> {
                      if (orderId == null) throw ApiException.invalid("Buy an item first");
                      // The ID comes only from this workspace. Load it first under the service's
                      // order lock so a concurrent expiry cannot leave a stale managed entity.
                      if (command.action() == Action.PAY) orders.payment(orderId, true);
                      else orders.cancel(workspace.owner, orderId);
                    }
                  }
                  return snapshot(workspace, orderId);
                });
        workspace.orderId = snapshot.order() == null ? null : snapshot.order().id();
        code =
            switch (command.action()) {
              case ADD_STOCK -> "STOCK_ADDED";
              case REMOVE_STOCK -> "STOCK_REMOVED";
              default -> snapshot.order().status().name();
            };
        if (command.action() == Action.PAY || command.action() == Action.CANCEL) {
          quantity = snapshot.order().quantity();
        }
      } catch (ApiException rejected) {
        // Read only after the failed write transaction has rolled back.
        snapshot = reads.execute(status -> snapshot(workspace, workspace.orderId));
        code = rejected.code();
        message = rejected.getMessage();
        level = "rejected";
        statusCode = rejected.status().value();
      }
      // Order transitions (including scheduler expiry) are observed after commit by the log.
      // Stock changes and rejected requests are recorded here only after their transaction ends.
      if (statusCode != 200
          || command.action() == Action.ADD_STOCK
          || command.action() == Action.REMOVE_STOCK) {
        activity.record(
            workspace.owner,
            command.action().name().toLowerCase(java.util.Locale.ROOT),
            code,
            level,
            message,
            quantity,
            snapshot.inventory(),
            snapshot.order());
      }
      var result = new Result(code, level, message, (System.nanoTime() - started) / 1_000_000);
      return new Outcome(statusCode, view(workspace, snapshot, result));
    }
  }

  private Snapshot snapshot(Workspace workspace, UUID orderId) {
    var order = orderId == null ? null : orders.get(workspace.owner, orderId);
    return new Snapshot(catalog.get(workspace.productId), order);
  }

  private State view(Workspace workspace, Snapshot snapshot, Result result) {
    return new State(
        snapshot.inventory(),
        snapshot.order(),
        activity.entries(workspace.owner, workspace.productId),
        clock.instant(),
        activity.streamId(),
        workspace.owner.substring(7, 15),
        Math.max(0, Duration.between(clock.instant(), workspace.nextActionAt).toMillis()),
        result);
  }

  public enum Action {
    ADD_STOCK,
    REMOVE_STOCK,
    BUY,
    PAY,
    CANCEL
  }

  public record Command(@NotNull Action action, @Min(1) @Max(10) int quantity) {}

  public static final class Workspace {
    private final UUID productId;
    private final String owner = "manual-" + UUID.randomUUID();
    private UUID orderId;
    private Instant nextActionAt = Instant.EPOCH;

    private Workspace(UUID productId) {
      this.productId = productId;
    }
  }

  private record Snapshot(ProductView inventory, OrderView order) {}

  public record Result(String code, String level, String message, long durationMs) {}

  public record State(
      ProductView inventory,
      OrderView order,
      List<ManualActivityLog.Entry> activity,
      Instant observedAt,
      String streamId,
      String visitorId,
      long retryAfterMs,
      Result result) {}

  public record Outcome(int statusCode, State state) {}
}
