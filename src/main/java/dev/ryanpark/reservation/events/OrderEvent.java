package dev.ryanpark.reservation.events;

import dev.ryanpark.reservation.order.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderEvent(
    int schemaVersion,
    UUID eventId,
    UUID orderId,
    UUID productId,
    int quantity,
    OrderStatus status,
    int revision,
    Instant occurredAt) {
  public void validate() {
    if (schemaVersion != 1
        || eventId == null
        || orderId == null
        || productId == null
        || occurredAt == null
        || status == null
        || quantity < 1
        || quantity > 10_000
        || revision < 1) throw new IllegalArgumentException("Invalid order event");
  }
}
