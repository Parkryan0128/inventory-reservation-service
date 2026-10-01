package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.inventory.cache.*;
import dev.inventory.inventory.*;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.order.*;
import dev.inventory.order.OrderDtos.ReserveRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;

@SpringBootTest(properties = "app.cache.enabled=true")
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext
class RedisIntegrationIT {
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired CatalogMetadataService metadata;
  @Autowired MetadataCache cache;
  @Autowired StringRedisTemplate redis;
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;

  UUID product() {
    return catalog
        .create(
            new CreateProduct(
                "REDIS-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Redis product",
                999,
                "USD",
                1))
        .id();
  }

  @Test
  @Order(1)
  void realRedisStoresMetadataWithBoundedTtl() {
    var id = product();
    var key = "catalog:v1:" + id;
    assertThat(metadata.get(id).priceCents()).isEqualTo(999);
    assertThat(redis.opsForValue().get(key)).contains("Redis product").doesNotContain("available");
    assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 30L);
    assertThat(metadata.get(id).id()).isEqualTo(id);
  }

  @Test
  @Order(2)
  void realRedisExpiresEntries() {
    var key = "ttl-test:" + UUID.randomUUID();
    cache.put(key, "value", Duration.ofMillis(150));
    assertThat(cache.get(key)).isEqualTo("value");
    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(cache.get(key)).isNull());
  }

  @Test
  @Order(3)
  void actualRedisOutageStillServesCatalogAndPreservesInventory() {
    var id = product();
    stop();
    assertThat(metadata.get(id).priceCents()).isEqualTo(999);
    orders.reserve("alice", new ReserveRequest(id, 1));
    assertThatThrownBy(() -> orders.reserve("bob", new ReserveRequest(id, 1)))
        .isInstanceOfSatisfying(
            dev.inventory.common.ApiException.class,
            e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
    assertThat(catalog.get(id).available()).isZero();
  }

  @AfterAll
  static void stop() {
    if (REDIS.isRunning()) REDIS.stop();
  }
}
