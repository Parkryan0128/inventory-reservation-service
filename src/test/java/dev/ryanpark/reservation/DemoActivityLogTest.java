package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ryanpark.reservation.demo.DemoActivityLog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DemoActivityLogTest {
  @Test
  void recordsObservationOrderRatherThanRequestNumberOrSuccessOrder() throws Exception {
    var now = Instant.parse("2026-01-01T00:00:00Z");
    var log = new DemoActivityLog(Clock.fixed(now, ZoneOffset.UTC));
    var recorded = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                if (!recorded.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Timed out");
                log.record("req-001", "customer-001", "reserve", "RESERVED", null, 1, "a", 5);
                return null;
              });
      var second =
          workers.submit(
              () -> {
                log.record(
                    "req-099", "customer-099", "reserve", "INSUFFICIENT_STOCK", null, 1, "b", 2);
                recorded.countDown();
              });
      second.get(5, TimeUnit.SECONDS);
      first.get(5, TimeUnit.SECONDS);
    }
    assertThat(log.entries())
        .extracting(DemoActivityLog.Entry::requestId)
        .containsExactly("req-099", "req-001");
    assertThat(log.entries()).extracting(DemoActivityLog.Entry::sequence).containsExactly(1, 2);
    assertThat(log.entries()).allSatisfy(e -> assertThat(e.recordedAt()).isEqualTo(now.toString()));
    var frozen = log.entries();
    log.snapshot(0);
    assertThat(frozen).hasSize(2);
    assertThatThrownBy(() -> frozen.clear()).isInstanceOf(UnsupportedOperationException.class);
  }
}
