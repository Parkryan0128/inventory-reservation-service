package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.inventory.events.OutboxEvent;
import dev.inventory.events.OutboxPoller;
import dev.inventory.events.OutboxRelay;
import dev.inventory.order.OrderDtos.ReserveRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRetryTest extends InventoryTestSupport {
  @Autowired OutboxRelay relay;
  @Autowired PlatformTransactionManager transactions;

  private OutboxEvent event() {
    var product = TestProducts.create(catalog, 1);
    var order = service.reserve("alice", new ReserveRequest(product.id(), 1));
    return outbox.findByOrderIdOrderByRevisionAsc(order.id()).getFirst();
  }

  @Test
  void repeatedFailuresBackOffUntilTheCapAndRecoverExactlyWhenDue() throws Exception {
    var event = event();
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publish(event.orderId(), event.payload());
    int attempts = 0;
    for (long delay : new long[] {2, 4, 8, 16, 32, 60, 60, 60}) {
      var started = clock.instant();
      assertThat(relay.publish(event.id())).isFalse();
      var failed = outbox.findById(event.id()).orElseThrow();
      assertThat(failed.attempts()).isEqualTo(++attempts);
      assertThat(failed.publishedAt()).isNull();
      assertThat(failed.nextAttemptAt()).isEqualTo(started.plusSeconds(delay));
      assertThat(failed.lastError()).isEqualTo("IllegalStateException");
      clock.set(failed.nextAttemptAt().minusMillis(1));
      assertThat(relay.publish(event.id())).isFalse();
      verify(publisher, times(attempts)).publish(event.orderId(), event.payload());
      clock.set(failed.nextAttemptAt());
    }

    doNothing().when(publisher).publish(event.orderId(), event.payload());
    assertThat(relay.publish(event.id())).isTrue();
    var published = outbox.findById(event.id()).orElseThrow();
    assertThat(published.attempts()).isEqualTo(attempts + 1);
    assertThat(published.publishedAt()).isEqualTo(clock.instant());
    assertThat(published.lastError()).isNull();
    assertThat(relay.publish(event.id())).isFalse();
    verify(publisher, times(attempts + 1)).publish(event.orderId(), event.payload());
  }

  @Test
  void interruptedPublishPreservesTheInterruptAndLeavesTheEventRetryable() throws Exception {
    var event = event();
    doAnswer(
            invocation -> {
              throw new InterruptedException("interrupted publish");
            })
        .when(publisher)
        .publish(event.orderId(), event.payload());
    try {
      assertThat(relay.publish(event.id())).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    var failed = outbox.findById(event.id()).orElseThrow();
    assertThat(failed.publishedAt()).isNull();
    assertThat(failed.attempts()).isEqualTo(1);
    assertThat(failed.lastError()).isEqualTo("InterruptedException");
  }

  @Test
  void rolledBackAcknowledgementRedeliversTheSameEventAndPayload() throws Exception {
    var event = event();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              assertThat(relay.publish(event.id())).isTrue();
              outbox.flush();
              assertThat(outbox.findById(event.id()).orElseThrow().publishedAt())
                  .isEqualTo(clock.instant());
              transaction.setRollbackOnly();
            });

    var pending = outbox.findById(event.id()).orElseThrow();
    assertThat(pending.publishedAt()).isNull();
    assertThat(pending.attempts()).isZero();
    assertThat(pending.nextAttemptAt()).isEqualTo(event.nextAttemptAt());
    assertThat(pending.payload()).isEqualTo(event.payload());
    verify(publisher).publish(event.orderId(), event.payload());

    clock.advance(Duration.ofSeconds(1));
    assertThat(relay.publish(event.id())).isTrue();
    assertThat(relay.publish(event.id())).isFalse();
    var published = outbox.findById(event.id()).orElseThrow();
    assertThat(published.publishedAt()).isEqualTo(clock.instant());
    assertThat(published.attempts()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
    verify(publisher, times(2)).publish(event.orderId(), event.payload());
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void rolledBackFailureDoesNotPersistRetryMetadata() throws Exception {
    var event = event();
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publish(event.orderId(), event.payload());
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              assertThat(relay.publish(event.id())).isFalse();
              outbox.flush();
              assertThat(outbox.findById(event.id()).orElseThrow().attempts()).isEqualTo(1);
              transaction.setRollbackOnly();
            });

    var pending = outbox.findById(event.id()).orElseThrow();
    assertThat(pending.attempts()).isZero();
    assertThat(pending.lastError()).isNull();
    assertThat(pending.nextAttemptAt()).isEqualTo(event.nextAttemptAt());
    assertThat(pending.publishedAt()).isNull();
    doNothing().when(publisher).publish(event.orderId(), event.payload());
    assertThat(relay.publish(event.id())).isTrue();
    assertThat(outbox.findById(event.id()).orElseThrow().attempts()).isEqualTo(1);
    verify(publisher, times(2)).publish(event.orderId(), event.payload());
  }

  @Test
  void failedEventDoesNotBlockLaterEventsOrRepublishSuccessfulOnes() throws Exception {
    var failed = event();
    clock.advance(Duration.ofSeconds(1));
    var healthy = event();
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publish(failed.orderId(), failed.payload());
    var poller = new OutboxPoller(outbox, relay, clock);

    poller.poll();
    var retry = outbox.findById(failed.id()).orElseThrow();
    assertThat(retry.publishedAt()).isNull();
    assertThat(retry.attempts()).isEqualTo(1);
    assertThat(retry.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(2));
    var published = outbox.findById(healthy.id()).orElseThrow();
    assertThat(published.publishedAt()).isEqualTo(clock.instant());
    assertThat(published.attempts()).isEqualTo(1);

    poller.poll();
    verify(publisher).publish(failed.orderId(), failed.payload());
    verify(publisher).publish(healthy.orderId(), healthy.payload());
    doNothing().when(publisher).publish(failed.orderId(), failed.payload());
    clock.set(retry.nextAttemptAt());
    poller.poll();

    var recovered = outbox.findById(failed.id()).orElseThrow();
    assertThat(recovered.publishedAt()).isEqualTo(clock.instant());
    assertThat(recovered.attempts()).isEqualTo(2);
    assertThat(recovered.lastError()).isNull();
    assertThat(outbox.findById(healthy.id()).orElseThrow().publishedAt())
        .isEqualTo(published.publishedAt());
    verify(publisher, times(2)).publish(failed.orderId(), failed.payload());
    verifyNoMoreInteractions(publisher);
  }

  @Test
  void pollerPublishesFiftyDueEventsThenTheRemainder() throws Exception {
    var due = new ArrayList<UUID>();
    for (int i = 0; i < 51; i++) {
      due.add(event().id());
      clock.advance(Duration.ofSeconds(1));
    }
    var future = event();
    jdbc.update(
        "UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
        java.sql.Timestamp.from(clock.instant().plusSeconds(60)),
        future.id());
    var poller = new OutboxPoller(outbox, relay, clock);
    poller.poll();
    for (var id : due.subList(0, 50)) {
      assertThat(outbox.findById(id).orElseThrow().publishedAt()).isNotNull();
    }
    assertThat(outbox.findById(due.getLast()).orElseThrow().publishedAt()).isNull();
    verify(publisher, times(50)).publish(any(), any());

    poller.poll();
    assertThat(outbox.findById(due.getLast()).orElseThrow().publishedAt()).isNotNull();
    assertThat(outbox.findById(future.id()).orElseThrow().publishedAt()).isNull();
    poller.poll();
    verify(publisher, times(51)).publish(any(), any());

    clock.advance(Duration.ofSeconds(60));
    poller.poll();
    assertThat(outbox.findById(future.id()).orElseThrow().publishedAt()).isNotNull();
    verify(publisher, times(52)).publish(any(), any());
  }
}
