package dev.inventory.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.inventory.common.ApiException;
import dev.inventory.demo.DemoRequests.Work;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;

class DemoRequestsTest {
  @Test
  void onlyConflictsBecomeRecordedOutcomes() throws Exception {
    var log = new DemoActivityLog(Clock.systemUTC());
    var conflict = ApiException.conflict("INSUFFICIENT_STOCK", "Not enough inventory");
    var result = DemoRequests.attempt(rejected(conflict), log);

    assertThat(result.code()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(result.orderId()).isNull();
    assertThat(log.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.requestId()).isEqualTo("req-001");
              assertThat(entry.actor()).isEqualTo("customer-001");
              assertThat(entry.operation()).isEqualTo("reserve");
              assertThat(entry.quantity()).isEqualTo(2);
              assertThat(entry.key()).isEqualTo("same-key");
              assertThat(entry.code()).isEqualTo(result.code());
              assertThat(entry.durationMs()).isEqualTo(result.durationMs());
            });

    for (var failure :
        List.of(
            ApiException.invalid("Invalid quantity"),
            ApiException.notFound("Product"),
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RETRY_LATER", "Resource is busy"))) {
      assertThatThrownBy(() -> DemoRequests.attempt(rejected(failure), log)).isSameAs(failure);
    }
    assertThat(log.entries()).hasSize(1);
  }

  @Test
  @Timeout(10)
  void parallelFailureInterruptsOutstandingWork() {
    var log = new DemoActivityLog(Clock.systemUTC());
    var entered = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var failure = new IllegalStateException("Database unavailable");
    var failing =
        new Work(
            "req-001",
            "customer-001",
            "reserve",
            1,
            "first",
            () -> {
              if (!entered.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Second worker did not start");
              throw failure;
            });
    var waiting =
        new Work(
            "req-002",
            "customer-002",
            "reserve",
            1,
            "second",
            () -> {
              entered.countDown();
              try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Worker was not cancelled");
              } catch (InterruptedException cancelled) {
                interrupted.set(true);
                throw cancelled;
              }
            });

    assertThatThrownBy(() -> DemoRequests.parallel(2, List.of(failing, waiting), log))
        .isInstanceOf(ExecutionException.class)
        .hasCause(failure);
    assertThat(interrupted).isTrue();
    assertThat(log.entries()).isEmpty();
  }

  private Work rejected(ApiException failure) {
    return new Work(
        "req-001",
        "customer-001",
        "reserve",
        2,
        "same-key",
        () -> {
          throw failure;
        });
  }
}
