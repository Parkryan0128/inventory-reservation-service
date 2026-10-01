package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.inventory.common.ApiException;
import dev.inventory.demo.DemoService;
import dev.inventory.inventory.CatalogService;
import dev.inventory.order.OrderPlacement;
import dev.inventory.order.OrderService;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoRunGuardTest {
  @Test
  void concurrentRunsAreRejectedAndFailuresReleaseTheGuard() throws Exception {
    var catalog = mock(CatalogService.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(catalog.create(any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Test release timed out");
              throw new IllegalStateException("Database unavailable");
            });
    var demo =
        new DemoService(
            catalog,
            mock(OrderService.class),
            mock(OrderPlacement.class),
            mock(JdbcTemplate.class),
            Clock.systemUTC());
    try (var worker = Executors.newSingleThreadExecutor()) {
      var first = worker.submit(() -> demo.run("contention"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(demo.busy()).isTrue();
        assertThatThrownBy(() -> demo.run("race"))
            .isInstanceOfSatisfying(
                ApiException.class, ex -> assertThat(ex.code()).isEqualTo("DEMO_BUSY"));
      } finally {
        release.countDown();
      }
      assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    }
    assertThat(demo.busy()).isFalse();
    assertThatThrownBy(() -> demo.run("race")).isInstanceOf(IllegalStateException.class);
    assertThat(demo.busy()).isFalse();
  }
}
