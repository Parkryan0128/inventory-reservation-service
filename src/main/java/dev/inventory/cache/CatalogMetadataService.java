package dev.inventory.cache;

import static dev.inventory.inventory.InventoryLimits.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.inventory.inventory.CatalogService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class CatalogMetadataService {
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  public record Metadata(UUID id, String sku, String name, long priceCents, String currency) {
    boolean validFor(UUID productId) {
      return productId.equals(id)
          && sku != null
          && sku.matches(SKU_PATTERN)
          && name != null
          && !name.isBlank()
          && name.length() <= MAX_NAME_LENGTH
          && priceCents >= 1
          && priceCents <= MAX_PRICE_CENTS
          && ("USD".equals(currency) || "CAD".equals(currency));
    }
  }

  private final CatalogService catalog;
  private final ObjectProvider<MetadataCache> caches;
  private final ObjectMapper json;
  private final Counter hits;
  private final Counter misses;
  private final Counter errors;

  public CatalogMetadataService(
      CatalogService catalog,
      ObjectProvider<MetadataCache> caches,
      ObjectMapper json,
      MeterRegistry metrics) {
    this.catalog = catalog;
    this.caches = caches;
    this.json = json;
    this.hits = metrics.counter("catalog.cache", "result", "hit");
    this.misses = metrics.counter("catalog.cache", "result", "miss");
    this.errors = metrics.counter("catalog.cache", "result", "error");
  }

  public Metadata get(UUID id) {
    var cache = caches.getIfAvailable();
    var key = "catalog:v1:" + id;
    var cached = cache == null ? null : read(cache, key, id);
    if (cached != null) {
      hits.increment();
      return cached;
    }

    misses.increment();
    var product = catalog.get(id);
    var value =
        new Metadata(
            product.id(), product.sku(), product.name(), product.priceCents(), product.currency());
    if (cache != null) write(cache, key, value);
    return value;
  }

  private Metadata read(MetadataCache cache, String key, UUID id) {
    try {
      var stored = cache.get(key);
      if (stored == null) return null;
      var value = json.readValue(stored, Metadata.class);
      if (value != null && value.validFor(id)) return value;
      errors.increment();
    } catch (DataAccessException | JsonProcessingException exception) {
      errors.increment();
    }
    return null;
  }

  private void write(MetadataCache cache, String key, Metadata value) {
    try {
      cache.put(key, json.writeValueAsString(value), CACHE_TTL);
    } catch (DataAccessException | JsonProcessingException exception) {
      errors.increment();
    }
  }
}
