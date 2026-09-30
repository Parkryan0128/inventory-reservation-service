package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.inventory.ProductDtos.ProductView;
import dev.ryanpark.reservation.order.OrderDtos.OrderView;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("demo")
public class ManualDemoService {
  private final CatalogService catalog;
  private final OrderService orders;
  private final Clock clock;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  public ManualDemoService(
      CatalogService catalog,
      OrderService orders,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.catalog = catalog;
    this.orders = orders;
    this.clock = clock;
    writes = new TransactionTemplate(transactions);
    reads = new TransactionTemplate(transactions);
    reads.setReadOnly(true);
    reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public Workspace create() {
    var product =
        catalog.create(
            new CreateProduct(
                "MANUAL-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Demo item",
                1200,
                "CAD",
                5));
    return new Workspace(product.id());
  }

  public State state(Workspace workspace) {
    synchronized (workspace) {
      var snapshot = reads.execute(status -> snapshot(workspace, workspace.orderId));
      if (!snapshot.equals(workspace.last)) {
        record(workspace, "refresh", "SNAPSHOT", "info", "Database snapshot", 0, 0, snapshot);
      }
      return view(workspace, snapshot);
    }
  }

  public Outcome act(Workspace workspace, Command command) {
    if (command == null
        || command.action() == null
        || command.quantity() < 1
        || command.quantity() > 10_000) {
      throw ApiException.invalid("An action and quantity between 1 and 10000 are required");
    }
    synchronized (workspace) {
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
      record(
          workspace,
          command.action().name().toLowerCase(Locale.ROOT),
          code,
          level,
          message,
          quantity,
          (System.nanoTime() - started) / 1_000_000,
          snapshot);
      return new Outcome(statusCode, view(workspace, snapshot));
    }
  }

  private Snapshot snapshot(Workspace workspace, UUID orderId) {
    var order = orderId == null ? null : orders.get(workspace.owner, orderId);
    return new Snapshot(catalog.get(workspace.productId), order);
  }

  private void record(
      Workspace workspace,
      String operation,
      String code,
      String level,
      String message,
      int quantity,
      long durationMs,
      Snapshot snapshot) {
    workspace.activity.addLast(
        new Entry(
            ++workspace.sequence,
            clock.instant(),
            operation,
            code,
            level,
            message,
            quantity,
            durationMs,
            snapshot.inventory(),
            snapshot.order()));
    while (workspace.activity.size() > 200) workspace.activity.removeFirst();
    workspace.last = snapshot;
  }

  private State view(Workspace workspace, Snapshot snapshot) {
    return new State(
        snapshot.inventory(), snapshot.order(), List.copyOf(workspace.activity), clock.instant());
  }

  public enum Action {
    ADD_STOCK,
    REMOVE_STOCK,
    BUY,
    PAY,
    CANCEL
  }

  public record Command(@NotNull Action action, @Min(1) @Max(10_000) int quantity) {}

  public static final class Workspace {
    private final UUID productId;
    private final String owner = "manual-" + UUID.randomUUID();
    private UUID orderId;
    private long sequence;
    private Snapshot last;
    private final ArrayDeque<Entry> activity = new ArrayDeque<>();

    private Workspace(UUID productId) {
      this.productId = productId;
    }
  }

  private record Snapshot(ProductView inventory, OrderView order) {}

  public record Entry(
      long sequence,
      Instant recordedAt,
      String operation,
      String code,
      String level,
      String message,
      int quantity,
      long durationMs,
      ProductView inventory,
      OrderView order) {}

  public record State(
      ProductView inventory, OrderView order, List<Entry> activity, Instant observedAt) {}

  public record Outcome(int statusCode, State state) {}
}
