package dev.inventory.inventory;

import static dev.inventory.inventory.InventoryLimits.*;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class ProductDtos {
  private ProductDtos() {}

  public record CreateProduct(
      @NotBlank @Pattern(regexp = SKU_PATTERN) String sku,
      @NotBlank @Size(max = MAX_NAME_LENGTH) String name,
      @Min(1) @Max(MAX_PRICE_CENTS) long priceCents,
      @NotNull @Pattern(regexp = CURRENCY_PATTERN) String currency,
      @Min(0) @Max(MAX_STOCK) int stock) {}

  public record ProductView(
      UUID id,
      String sku,
      String name,
      long priceCents,
      String currency,
      int available,
      int reserved,
      int sold,
      int initialStock) {
    public static ProductView from(Product product) {
      return new ProductView(
          product.id(),
          product.sku(),
          product.name(),
          product.priceCents(),
          product.currency(),
          product.available(),
          product.reserved(),
          product.sold(),
          product.initialStock());
    }
  }
}
