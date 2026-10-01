package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.inventory.cache.*;
import dev.inventory.inventory.*;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.order.*;
import dev.inventory.order.OrderDtos.ReserveRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class MetadataCacheTest {
  @Autowired CatalogService catalog;
  @Autowired CatalogMetadataService metadata;
  @Autowired OrderService orders;
  @MockitoBean MetadataCache cache;

  UUID product() {
    return catalog
        .create(
            new CreateProduct(
                "CACHE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Cache product",
                999,
                "USD",
                1))
        .id();
  }

  @Test
  void cacheOutageFallsBackToDatabaseAndDoesNotAuthorizeInventory() {
    var id = product();
    when(cache.get(anyString()))
        .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
    doThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"))
        .when(cache)
        .put(anyString(), anyString(), any());
    assertThat(metadata.get(id).priceCents()).isEqualTo(999);
    orders.reserve("alice", new ReserveRequest(id, 1));
    assertThatThrownBy(() -> orders.reserve("bob", new ReserveRequest(id, 1)))
        .isInstanceOfSatisfying(
            dev.inventory.common.ApiException.class,
            e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
    assertThat(catalog.get(id).available()).isZero();
  }

  @Test
  void malformedOrWrongProductCacheFallsBack() {
    var id = product();
    when(cache.get(anyString())).thenReturn("bad-json", "{\"id\":\"" + UUID.randomUUID() + "\"}");
    assertThat(metadata.get(id).id()).isEqualTo(id);
    assertThat(metadata.get(id).id()).isEqualTo(id);
  }

  @Test
  void cachedPriceDoesNotAffectReservationPrice() {
    var id = product();
    when(cache.get(anyString()))
        .thenReturn(
            "{\"id\":\""
                + id
                + "\",\"sku\":\"CACHED\",\"name\":\"Cached\",\"priceCents\":123,\"currency\":\"USD\"}");
    assertThat(metadata.get(id).priceCents()).isEqualTo(123);
    assertThat(orders.reserve("alice", new ReserveRequest(id, 1)).unitPriceCents()).isEqualTo(999);
  }

  @Test
  void incompleteCacheEntryFallsBackToDatabase() {
    var id = product();
    when(cache.get(anyString())).thenReturn("{\"id\":\"" + id + "\"}");
    var result = metadata.get(id);
    assertThat(result.name()).isEqualTo("Cache product");
    assertThat(result.priceCents()).isEqualTo(999);
    assertThat(result.currency()).isEqualTo("USD");
  }

  @Test
  void programmingErrorsAreNotHiddenAsCacheOutages() {
    when(cache.get(anyString())).thenThrow(new IllegalStateException("adapter bug"));
    assertThatThrownBy(() -> metadata.get(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("adapter bug");
  }
}
