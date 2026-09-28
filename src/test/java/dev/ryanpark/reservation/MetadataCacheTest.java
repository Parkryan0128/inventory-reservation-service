package dev.ryanpark.reservation;
import dev.ryanpark.reservation.cache.*;
import dev.ryanpark.reservation.inventory.*;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.*;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@SpringBootTest @ActiveProfiles("test")
class MetadataCacheTest {
    @Autowired CatalogService catalog;
    @Autowired CatalogMetadataService metadata;
    @Autowired OrderService orders;
    @MockitoBean MetadataCache cache;
    UUID product() { return catalog.create(new CreateProduct("CACHE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "Cache product", 999, "USD", 1)).id(); }
    @Test void cacheOutageFallsBackToDatabaseAndDoesNotAuthorizeInventory() {
        var id = product();
        when(cache.get(anyString())).thenThrow(new IllegalStateException("offline"));
        doThrow(new IllegalStateException("offline")).when(cache).put(anyString(), anyString(), any());
        assertThat(metadata.get(id).priceCents()).isEqualTo(999);
        orders.reserve("alice", new ReserveRequest(id, 1));
        assertThatThrownBy(() -> orders.reserve("bob", new ReserveRequest(id, 1))).isInstanceOfSatisfying(dev.ryanpark.reservation.common.ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
        assertThat(catalog.get(id).available()).isZero();
    }
    @Test void malformedOrWrongProductCacheFallsBack() {
        var id = product();
        when(cache.get(anyString())).thenReturn("bad-json", "{\"id\":\"" + UUID.randomUUID() + "\"}");
        assertThat(metadata.get(id).id()).isEqualTo(id); assertThat(metadata.get(id).id()).isEqualTo(id);
    }
    @Test void cachedPriceDoesNotAffectReservationPrice() {
        var id = product();
        when(cache.get(anyString())).thenReturn("{\"id\":\"" + id + "\",\"sku\":\"CACHED\",\"name\":\"Cached\",\"priceCents\":123,\"currency\":\"USD\"}");
        assertThat(metadata.get(id).priceCents()).isEqualTo(123);
        assertThat(orders.reserve("alice", new ReserveRequest(id, 1)).unitPriceCents()).isEqualTo(999);
    }
}
