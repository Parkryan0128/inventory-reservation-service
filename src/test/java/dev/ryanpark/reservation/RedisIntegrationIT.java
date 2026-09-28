package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import dev.ryanpark.reservation.cache.*;
import dev.ryanpark.reservation.inventory.*;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.*;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import java.net.*;
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
  static GenericContainer<?> container;
  static Process process;
  static String host;
  static int port;

  static {
    try {
      var binary = System.getenv("TEST_REDIS_BINARY");
      if (binary == null || binary.isBlank()) {
        container = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
        container.start();
        host = container.getHost();
        port = container.getMappedPort(6379);
      } else {
        host = "127.0.0.1";
        try (var socket = new ServerSocket(0)) {
          port = socket.getLocalPort();
        }
        process =
            new ProcessBuilder(
                    binary,
                    "--bind",
                    host,
                    "--port",
                    String.valueOf(port),
                    "--save",
                    "",
                    "--appendonly",
                    "no")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        await()
            .pollInSameThread()
            .atMost(Duration.ofSeconds(10))
            .until(
                () -> {
                  try (var socket = new Socket(host, port)) {
                    return socket.isConnected();
                  } catch (Exception exception) {
                    return false;
                  }
                });
      }
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", () -> host);
    registry.add("spring.data.redis.port", () -> port);
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
  void actualRedisOutageStillServesCatalogAndPreservesInventory() throws Exception {
    var id = product();
    stop();
    assertThat(metadata.get(id).priceCents()).isEqualTo(999);
    orders.reserve("alice", new ReserveRequest(id, 1));
    assertThatThrownBy(() -> orders.reserve("bob", new ReserveRequest(id, 1)))
        .isInstanceOfSatisfying(
            dev.ryanpark.reservation.common.ApiException.class,
            e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_STOCK"));
    assertThat(catalog.get(id).available()).isZero();
  }

  @AfterAll
  static void stop() throws Exception {
    if (container != null && container.isRunning()) container.stop();
    if (process != null && process.isAlive()) {
      process.destroy();
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    }
  }
}
