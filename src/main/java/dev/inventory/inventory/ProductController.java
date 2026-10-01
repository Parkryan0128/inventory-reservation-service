package dev.inventory.inventory;

import static dev.inventory.inventory.ProductDtos.CreateProduct;
import static dev.inventory.inventory.ProductDtos.ProductView;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {
  private final CatalogService catalog;

  public ProductController(CatalogService catalog) {
    this.catalog = catalog;
  }

  @PostMapping
  ResponseEntity<ProductView> create(@Valid @RequestBody CreateProduct request) {
    var product = catalog.create(request);
    return ResponseEntity.created(URI.create("/api/products/" + product.id())).body(product);
  }

  @GetMapping
  List<ProductView> list(@RequestParam(defaultValue = "0") int page) {
    return catalog.list(page);
  }

  @GetMapping("/{id}")
  ProductView get(@PathVariable UUID id) {
    return catalog.get(id);
  }
}
