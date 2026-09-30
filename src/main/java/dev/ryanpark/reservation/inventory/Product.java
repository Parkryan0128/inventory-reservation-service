package dev.ryanpark.reservation.inventory;

import dev.ryanpark.reservation.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 64)
  private String sku;

  @Column(nullable = false, length = 160)
  private String name;

  private long priceCents;

  @Column(length = 3)
  private String currency;

  private int available;
  private int reserved;
  private int sold;
  private int initialStock;

  protected Product() {}

  Product(String sku, String name, long priceCents, String currency, int stock) {
    this.id = UUID.randomUUID();
    this.sku = sku;
    this.name = name;
    this.priceCents = priceCents;
    this.currency = currency;
    this.available = stock;
    this.initialStock = stock;
  }

  public void reserve(int quantity) {
    if (quantity < 1 || quantity > 10_000)
      throw ApiException.invalid("Quantity must be between 1 and 10000");
    if (available < quantity)
      throw ApiException.conflict("INSUFFICIENT_STOCK", "Not enough inventory");
    available -= quantity;
    reserved += quantity;
  }

  public void release(int quantity) {
    checkReserved(quantity);
    reserved -= quantity;
    available += quantity;
  }

  public void confirm(int quantity) {
    checkReserved(quantity);
    reserved -= quantity;
    sold += quantity;
  }

  public void adjustStock(int delta) {
    if (delta == 0 || delta < -10_000 || delta > 10_000)
      throw ApiException.invalid("Stock adjustment must be between 1 and 10000 units");
    if (delta < 0 && available < -delta)
      throw ApiException.conflict("INSUFFICIENT_STOCK", "Only available stock can be removed");
    if ((long) initialStock + delta > 1_000_000)
      throw ApiException.conflict("STOCK_LIMIT", "Total stock cannot exceed 1000000 units");
    available += delta;
    // The existing balance column is the stock baseline, including subsequent adjustments.
    initialStock += delta;
  }

  private void checkReserved(int quantity) {
    if (quantity < 1 || reserved < quantity)
      throw new IllegalStateException("Invalid inventory transition");
  }

  public UUID id() {
    return id;
  }

  public String sku() {
    return sku;
  }

  public String name() {
    return name;
  }

  public long priceCents() {
    return priceCents;
  }

  public String currency() {
    return currency;
  }

  public int available() {
    return available;
  }

  public int reserved() {
    return reserved;
  }

  public int sold() {
    return sold;
  }

  public int initialStock() {
    return initialStock;
  }
}
