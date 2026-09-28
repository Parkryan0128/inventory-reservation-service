package dev.ryanpark.reservation.cache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ryanpark.reservation.inventory.CatalogService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
@Service
public class CatalogMetadataService {
    public record Metadata(UUID id, String sku, String name, long priceCents, String currency) {}
    private final CatalogService catalog;
    private final ObjectProvider<MetadataCache> caches;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    public CatalogMetadataService(CatalogService catalog, ObjectProvider<MetadataCache> caches, ObjectMapper json, MeterRegistry metrics) {
        this.catalog = catalog; this.caches = caches; this.json = json; this.metrics = metrics;
    }
    public Metadata get(UUID id) {
        var cache = caches.getIfAvailable(); var key = "catalog:v1:" + id;
        if (cache != null) {
            try {
                var stored = cache.get(key);
                if (stored != null) {
                    var value = json.readValue(stored, Metadata.class);
                    if (value != null && id.equals(value.id())) {
                        metrics.counter("catalog.cache", "result", "hit").increment(); return value;
                    }
                }
            } catch (RuntimeException | JsonProcessingException ignored) {
                metrics.counter("catalog.cache", "result", "error").increment();
            }
        }
        metrics.counter("catalog.cache", "result", "miss").increment();
        var product = catalog.get(id);
        var value = new Metadata(product.id(), product.sku(), product.name(), product.priceCents(), product.currency());
        if (cache != null) {
            try { cache.put(key, json.writeValueAsString(value), Duration.ofSeconds(30)); }
            catch (RuntimeException | JsonProcessingException ignored) { metrics.counter("catalog.cache", "result", "error").increment(); }
        }
        return value;
    }
}
