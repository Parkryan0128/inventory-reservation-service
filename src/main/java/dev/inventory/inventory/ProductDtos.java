package dev.inventory.inventory;

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
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,64}") String sku,
      @NotBlank @Size(max = 160) String name,
      @Min(1) @Max(1_000_000_000) long priceCents,
      @NotNull @Pattern(regexp = "USD|CAD") String currency,
      @Min(0) @Max(1_000_000) int stock) {}

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
