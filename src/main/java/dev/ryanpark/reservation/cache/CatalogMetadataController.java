package dev.ryanpark.reservation.cache;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogMetadataController {
  private final CatalogMetadataService service;

  public CatalogMetadataController(CatalogMetadataService service) {
    this.service = service;
  }

  @GetMapping("/{id}")
  CatalogMetadataService.Metadata get(@PathVariable UUID id) {
    return service.get(id);
  }
}
