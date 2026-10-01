package dev.inventory.demo;

import dev.inventory.events.OrderChanged;
import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
@Profile({"demo", "public-demo"})
public class ManualActivityLog {
  private final Clock clock;
  private final String streamId = UUID.randomUUID().toString();
  private final ArrayDeque<RecordedEntry> entries = new ArrayDeque<>();
  private long sequence;

  public ManualActivityLog(Clock clock) {
    this.clock = clock;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void orderChanged(OrderChanged change) {
    if (!SharedDemoProduct.SKU.equals(change.inventory().sku())) return;
    var order = change.order();
    var operation =
        switch (order.status()) {
          case RESERVED -> "buy";
          case CONFIRMED -> "payment";
          case CANCELLED -> "cancel";
          case PAYMENT_FAILED -> "payment_failed";
          case EXPIRED -> "expiry";
        };
    record(
        order.status() == OrderStatus.EXPIRED ? null : order.ownerId(),
        operation,
        order.status().name(),
        "ok",
        "Order transition committed",
        order.quantity(),
        change.inventory(),
        order);
  }

  public synchronized void record(
      String owner,
      String operation,
      String code,
      String level,
      String message,
      int quantity,
      ProductView inventory,
      OrderView order) {
    // Sequence is observation order, not a reconstructed database commit order.
    entries.addLast(
        new RecordedEntry(
            ++sequence,
            clock.instant(),
            owner,
            operation,
            code,
            level,
            message,
            quantity,
            inventory,
            order == null
                ? null
                : new LogOrder(
                    order.id(),
                    order.productId(),
                    order.quantity(),
                    order.status(),
                    order.expiresAt())));
    while (entries.size() > 200) entries.removeFirst();
  }

  public synchronized List<Entry> entries(String viewer, UUID productId) {
    return entries.stream()
        .filter(e -> e.inventory().id().equals(productId))
        .map(
            e ->
                new Entry(
                    e.sequence(),
                    e.recordedAt(),
                    actor(e.owner(), viewer),
                    e.operation(),
                    e.code(),
                    e.level(),
                    e.message(),
                    e.quantity(),
                    e.inventory(),
                    e.order()))
        .toList();
  }

  public String streamId() {
    return streamId;
  }

  private String actor(String owner, String viewer) {
    if (owner == null) return "System";
    if (owner.equals(viewer)) return "You";
    // Manual owners are generated UUIDs; expose only a short anonymous label, never the owner ID.
    return owner.startsWith("manual-") && owner.length() >= 15
        ? "Visitor " + owner.substring(7, 15)
        : "Operator";
  }

  public record LogOrder(
      UUID id, UUID productId, int quantity, OrderStatus status, Instant expiresAt) {}

  public record Entry(
      long sequence,
      Instant recordedAt,
      String actor,
      String operation,
      String code,
      String level,
      String message,
      int quantity,
      ProductView inventory,
      LogOrder order) {}

  private record RecordedEntry(
      long sequence,
      Instant recordedAt,
      String owner,
      String operation,
      String code,
      String level,
      String message,
      int quantity,
      ProductView inventory,
      LogOrder order) {}
}
