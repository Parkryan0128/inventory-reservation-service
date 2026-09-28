package dev.ryanpark.reservation.order;

import dev.ryanpark.reservation.inventory.Product;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String ownerId;
    @Column(nullable = false) private UUID productId;
    private int quantity;
    private long unitPriceCents;
    @Column(length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(length = 24) private OrderStatus status;
    private Instant createdAt;
    private Instant expiresAt;

    protected Reservation() {}
    Reservation(String owner, Product product, int quantity, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.ownerId = owner; this.productId = product.id();
        this.quantity = quantity; this.unitPriceCents = product.priceCents(); this.currency = product.currency();
        this.status = OrderStatus.RESERVED; this.createdAt = now; this.expiresAt = expiresAt;
    }
    public UUID id() { return id; }
    public String ownerId() { return ownerId; }
    public UUID productId() { return productId; }
    public int quantity() { return quantity; }
    public long unitPriceCents() { return unitPriceCents; }
    public String currency() { return currency; }
    public OrderStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
}
