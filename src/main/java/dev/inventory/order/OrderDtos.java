package dev.inventory.order;

import static dev.inventory.inventory.InventoryLimits.MAX_QUANTITY;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class OrderDtos {
  private OrderDtos() {}

  public record ReserveRequest(@NotNull UUID productId, @Min(1) @Max(MAX_QUANTITY) int quantity) {}

  public record PaymentRequest(@NotNull Boolean success) {}

  public record OrderView(
      UUID id,
      String ownerId,
      UUID productId,
      int quantity,
      long unitPriceCents,
      long totalPriceCents,
      String currency,
      OrderStatus status,
      Instant createdAt,
      Instant expiresAt) {
    public static OrderView from(Reservation order) {
      return new OrderView(
          order.id(),
          order.ownerId(),
          order.productId(),
          order.quantity(),
          order.unitPriceCents(),
          Math.multiplyExact(order.unitPriceCents(), order.quantity()),
          order.currency(),
          order.status(),
          order.createdAt(),
          order.expiresAt());
    }
  }
}
