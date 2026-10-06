package dev.inventory;

import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.inventory.ProductDtos.ProductView;
import java.util.Locale;
import java.util.UUID;

final class TestProducts {
  static ProductView create(CatalogService catalog, int stock) {
    return create(catalog, stock, 1200, "USD");
  }

  static ProductView create(CatalogService catalog, int stock, long price, String currency) {
    return catalog.create(
        new CreateProduct(
            "TEST-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
            "Test product",
            price,
            currency,
            stock));
  }

  private TestProducts() {}
}
