package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.Product;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.inventory.ProductRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Profile("demo")
public class SharedDemoProduct {
  public static final String SKU = "MANUAL-SHARED";
  private final CatalogService catalog;
  private final ProductRepository products;

  public SharedDemoProduct(CatalogService catalog, ProductRepository products) {
    this.catalog = catalog;
    this.products = products;
  }

  public synchronized UUID id() {
    var existing = products.findBySku(SKU);
    if (existing.isPresent()) return existing.get().id();
    try {
      return catalog.create(new CreateProduct(SKU, "Shared demo item", 1200, "CAD", 5)).id();
    } catch (DataIntegrityViolationException collision) {
      // A separate application may have initialized it first. The create transaction has ended.
      return products.findBySku(SKU).map(Product::id).orElseThrow(() -> collision);
    }
  }
}
