package dev.inventory.order;

import dev.inventory.inventory.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {
  @Id private UUID id;

  @Column(nullable = false, length = 100)
  private String ownerId;

  @Column(nullable = false)
  private UUID productId;

  private int quantity;
  private long unitPriceCents;

  @Column(length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(length = 24)
  private OrderStatus status;

  private Instant createdAt;
  private Instant expiresAt;

  @Column(nullable = false, length = 80)
  private String idempotencyKey;

  private int revision;

  protected Reservation() {}

  Reservation(
      String owner, String key, Product product, int quantity, Instant now, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.ownerId = owner;
    this.productId = product.id();
    this.quantity = quantity;
    this.unitPriceCents = product.priceCents();
    this.currency = product.currency();
    this.status = OrderStatus.RESERVED;
    this.createdAt = now;
    this.expiresAt = expiresAt;
    this.idempotencyKey = key;
    this.revision = 1;
  }

  void transitionTo(OrderStatus target) {
    if (status != OrderStatus.RESERVED || target == OrderStatus.RESERVED)
      throw new IllegalStateException("Invalid order transition");
    status = target;
    revision++;
  }

  public UUID id() {
    return id;
  }

  public String ownerId() {
    return ownerId;
  }

  public UUID productId() {
    return productId;
  }

  public int quantity() {
    return quantity;
  }

  public long unitPriceCents() {
    return unitPriceCents;
  }

  public String currency() {
    return currency;
  }

  public OrderStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public int revision() {
    return revision;
  }
}
