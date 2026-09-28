package dev.ryanpark.reservation.inventory;

import static dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import static dev.ryanpark.reservation.inventory.ProductDtos.ProductView;

import dev.ryanpark.reservation.common.ApiException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class CatalogService {
  private final ProductRepository products;

  public CatalogService(ProductRepository products) {
    this.products = products;
  }

  @Transactional
  public ProductView create(@Valid CreateProduct request) {
    return ProductView.from(
        products.saveAndFlush(
            new Product(
                request.sku(),
                request.name(),
                request.priceCents(),
                request.currency(),
                request.stock())));
  }

  @Transactional(readOnly = true)
  public ProductView get(UUID id) {
    return ProductView.from(
        products.findById(id).orElseThrow(() -> ApiException.notFound("Product")));
  }

  @Transactional(readOnly = true)
  public List<ProductView> list(int page) {
    if (page < 0) throw ApiException.invalid("Page cannot be negative");
    return products.findAll(PageRequest.of(page, 50, Sort.by("sku"))).stream()
        .map(ProductView::from)
        .toList();
  }
}
