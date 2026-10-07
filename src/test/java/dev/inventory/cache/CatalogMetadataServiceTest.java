package dev.inventory.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.ProductView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;

class CatalogMetadataServiceTest {
  final UUID id = UUID.randomUUID();
  final String key = "catalog:v1:" + id;
  final CatalogService catalog = mock(CatalogService.class);
  final MetadataCache cache = mock(MetadataCache.class);
  final ObjectMapper json = new ObjectMapper();
  final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  final ProductView product =
      new ProductView(id, "DB-SKU", "Database product", 999, "USD", 4, 0, 0, 4);
  ObjectProvider<MetadataCache> provider;
  CatalogMetadataService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(cache);
    service = new CatalogMetadataService(catalog, provider, json, metrics);
  }

  @Test
  void missStoresOnlyMetadataWithThirtySecondTtl() throws Exception {
    when(catalog.get(id)).thenReturn(product);
    var result = service.get(id);
    assertThat(result)
        .isEqualTo(
            new CatalogMetadataService.Metadata(id, "DB-SKU", "Database product", 999, "USD"));
    verify(cache).put(key, json.writeValueAsString(result), Duration.ofSeconds(30));
    assertMetrics(0, 1, 0);
  }

  @Test
  void absentCacheStillReturnsDatabaseMetadata() {
    when(provider.getIfAvailable()).thenReturn(null);
    when(catalog.get(id)).thenReturn(product);
    assertThat(service.get(id).id()).isEqualTo(id);
    verifyNoInteractions(cache);
    assertMetrics(0, 1, 0);
  }

  @ParameterizedTest
  @MethodSource("validMetadata")
  void validHitAvoidsDatabaseAndDoesNotRefreshTtl(
      String sku, String name, long price, String currency) throws Exception {
    var value = new CatalogMetadataService.Metadata(id, sku, name, price, currency);
    when(cache.get(key)).thenReturn(json.writeValueAsString(value));
    assertThat(service.get(id)).isEqualTo(value);
    verifyNoInteractions(catalog);
    verify(cache).get(key);
    verifyNoMoreInteractions(cache);
    assertMetrics(1, 0, 0);
  }

  static Stream<Arguments> validMetadata() {
    return Stream.of(
        Arguments.of("A", "A", 1L, "USD"),
        Arguments.of("A".repeat(64), "N".repeat(160), 1_000_000_000L, "CAD"));
  }

  @ParameterizedTest
  @MethodSource("invalidMetadata")
  void invalidCacheFieldsFallBackAndReplaceEntry(String field, Object value) throws Exception {
    var tree = json.valueToTree(new CatalogMetadataService.Metadata(id, "SKU", "Name", 100, "USD"));
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree).set(field, json.valueToTree(value));
    when(cache.get(key)).thenReturn(json.writeValueAsString(tree));
    when(catalog.get(id)).thenReturn(product);
    var result = service.get(id);
    assertThat(result.name()).isEqualTo("Database product");
    verify(cache).put(key, json.writeValueAsString(result), Duration.ofSeconds(30));
    assertMetrics(0, 1, 1);
  }

  static Stream<Arguments> invalidMetadata() {
    return Stream.of(
        Arguments.of("id", null),
        Arguments.of("id", UUID.randomUUID().toString()),
        Arguments.of("sku", null),
        Arguments.of("sku", ""),
        Arguments.of("sku", "lowercase"),
        Arguments.of("sku", "A".repeat(65)),
        Arguments.of("name", null),
        Arguments.of("name", " \t"),
        Arguments.of("name", "N".repeat(161)),
        Arguments.of("priceCents", 0),
        Arguments.of("priceCents", -1),
        Arguments.of("priceCents", 1_000_000_001L),
        Arguments.of("currency", null),
        Arguments.of("currency", "EUR"),
        Arguments.of("currency", "usd"));
  }

  @Test
  void writeOutagePreservesDatabaseResultAndRecordsError() {
    when(catalog.get(id)).thenReturn(product);
    doThrow(new RedisConnectionFailureException("offline"))
        .when(cache)
        .put(eq(key), anyString(), any());
    assertThat(service.get(id).priceCents()).isEqualTo(999);
    assertMetrics(0, 1, 1);
  }

  @Test
  void missingProductIsNotCached() {
    var failure = new IllegalArgumentException("missing product");
    when(catalog.get(id)).thenThrow(failure);
    assertThatThrownBy(() -> service.get(id)).isSameAs(failure);
    verify(cache).get(key);
    verifyNoMoreInteractions(cache);
    assertMetrics(0, 1, 0);
  }

  @Test
  void programmingErrorDuringWriteIsPropagated() {
    when(catalog.get(id)).thenReturn(product);
    doThrow(new IllegalStateException("adapter bug")).when(cache).put(eq(key), anyString(), any());
    assertThatThrownBy(() -> service.get(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("adapter bug");
    assertMetrics(0, 1, 0);
  }

  void assertMetrics(double hits, double misses, double errors) {
    assertThat(metrics.get("catalog.cache").tag("result", "hit").counter().count()).isEqualTo(hits);
    assertThat(metrics.get("catalog.cache").tag("result", "miss").counter().count())
        .isEqualTo(misses);
    assertThat(metrics.get("catalog.cache").tag("result", "error").counter().count())
        .isEqualTo(errors);
  }
}
