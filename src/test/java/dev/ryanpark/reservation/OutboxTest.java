package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.*;

import dev.ryanpark.reservation.events.*;
import dev.ryanpark.reservation.inventory.*;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.*;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(OutboxTest.PublisherConfiguration.class)
class OutboxTest {
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;
  @Autowired OutboxRepository outbox;
  @Autowired OutboxRelay relay;
  @Autowired OrderEventConsumer consumer;
  @Autowired ProcessedEventRepository receipts;
  @Autowired RecordingPublisher publisher;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @BeforeEach
  void resetPublisher() {
    publisher.fail = false;
    publisher.calls.set(0);
  }

  private UUID product() {
    return catalog
        .create(
            new CreateProduct(
                "EVT-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT),
                "Event product",
                100,
                "USD",
                10))
        .id();
  }

  private UUID reserve() {
    return orders.reserve("alice", new ReserveRequest(product(), 1)).id();
  }

  @Test
  void stateChangesAndEventsCommitTogether() {
    var order = reserve();
    orders.payment(order, true);
    orders.payment(order, true);
    var events = outbox.findByOrderIdOrderByRevisionAsc(order);
    assertThat(events).extracting(OutboxEvent::eventType).containsExactly("RESERVED", "CONFIRMED");
    assertThat(events).extracting(OutboxEvent::revision).containsExactly(1, 2);
  }

  @Test
  void rollbackCannotLeakOutboxEvents() {
    var product = product();
    var before = outbox.count();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .execute(
                        status -> {
                          orders.reserve("alice", new ReserveRequest(product, 2));
                          throw new IllegalStateException("rollback after recording event");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(outbox.count()).isEqualTo(before);
    assertThat(catalog.get(product).available()).isEqualTo(10);
  }

  @Test
  void failedPublishRemainsDurableAndCanRetry() {
    var event = outbox.findByOrderIdOrderByRevisionAsc(reserve()).getFirst();
    publisher.fail = true;
    assertThat(relay.publish(event.id())).isFalse();
    var failed = outbox.findById(event.id()).orElseThrow();
    assertThat(failed.publishedAt()).isNull();
    assertThat(failed.attempts()).isEqualTo(1);
    assertThat(failed.lastError()).isEqualTo("IllegalStateException");
    assertThat(relay.publish(event.id())).isFalse();
    assertThat(publisher.calls.get()).isEqualTo(1);
    publisher.fail = false;
    jdbc.update(
        "UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
        Timestamp.from(Instant.EPOCH),
        event.id());
    assertThat(relay.publish(event.id())).isTrue();
    assertThat(relay.publish(event.id())).isFalse();
    assertThat(outbox.findById(event.id()).orElseThrow().publishedAt()).isNotNull();
    assertThat(publisher.calls.get()).isEqualTo(2);
  }

  @Test
  void concurrentRelaysPublishAnAcknowledgedRowOnce() throws Exception {
    var event = outbox.findByOrderIdOrderByRevisionAsc(reserve()).getFirst();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> relay.publish(event.id()));
      var b = pool.submit(() -> relay.publish(event.id()));
      assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(publisher.calls.get()).isEqualTo(1);
  }

  @Test
  void duplicateAndOutOfOrderDeliveryProduceOneReceiptPerEvent() {
    var order = reserve();
    orders.payment(order, true);
    var events = outbox.findByOrderIdOrderByRevisionAsc(order);
    consumer.accept(events.get(1).payload());
    consumer.accept(events.getFirst().payload());
    consumer.accept(events.getFirst().payload());
    consumer.accept(events.get(1).payload());
    assertThat(receipts.countByOrderId(order)).isEqualTo(2);
  }

  @Test
  void concurrentDuplicateConsumersRemainIdempotent() throws Exception {
    var event = outbox.findByOrderIdOrderByRevisionAsc(reserve()).getFirst();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> consumer.accept(event.payload()));
      var b = pool.submit(() -> consumer.accept(event.payload()));
      a.get(15, TimeUnit.SECONDS);
      b.get(15, TimeUnit.SECONDS);
    }
    assertThat(receipts.countByOrderId(event.orderId())).isEqualTo(1);
  }

  @Test
  void conflictingDuplicatePayloadIsRejected() {
    var event = outbox.findByOrderIdOrderByRevisionAsc(reserve()).getFirst();
    consumer.accept(event.payload());
    assertThatThrownBy(() -> consumer.accept(event.payload().replace("RESERVED", "CONFIRMED")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(receipts.countByOrderId(event.orderId())).isEqualTo(1);
  }

  @Test
  void malformedEventsCannotCreateReceipts() {
    var before = receipts.count();
    assertThatThrownBy(() -> consumer.accept("not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> consumer.accept("{}")).isInstanceOf(IllegalArgumentException.class);
    assertThat(receipts.count()).isEqualTo(before);
  }

  @TestConfiguration
  static class PublisherConfiguration {
    @Bean
    RecordingPublisher recordingPublisher() {
      return new RecordingPublisher();
    }
  }

  static class RecordingPublisher implements EventPublisher {
    volatile boolean fail;
    final AtomicInteger calls = new AtomicInteger();

    public void publish(UUID orderId, String payload) {
      calls.incrementAndGet();
      if (fail) throw new IllegalStateException("simulated broker outage");
    }
  }
}
