package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.inventory.events.*;
import dev.inventory.order.OrderStatus;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class EventConsumerBoundaryTest extends InventoryTestSupport {
  @Autowired OrderEventConsumer consumer;
  @Autowired ProcessedEventRepository receipts;
  @Autowired ObjectMapper json;
  @Autowired PlatformTransactionManager transactions;

  OrderEvent event() {
    return new OrderEvent(
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        OrderStatus.RESERVED,
        1,
        START);
  }

  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void acceptsEveryStatusAndPreservesPayload(OrderStatus status) throws Exception {
    var base = event();
    var event =
        new OrderEvent(
            1, base.eventId(), base.orderId(), base.productId(), 10_000, status, 2, START);
    var payload = json.writeValueAsString(event);
    consumer.accept(payload);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(payload);
    assertThat(receipts.countByOrderId(event.orderId())).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT event_type FROM processed_order_events WHERE id = ?",
                String.class,
                event.eventId()))
        .isEqualTo(status.name());
    assertThat(orders.count()).isZero();
    assertThat(outbox.count()).isZero();
  }

  @ParameterizedTest
  @MethodSource("invalidFields")
  void invalidFieldsCannotCreateReceipt(String field, Object value) throws Exception {
    ObjectNode payload = json.valueToTree(event());
    payload.set(field, json.valueToTree(value));
    assertThatThrownBy(() -> consumer.accept(json.writeValueAsString(payload)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(receipts.count()).isZero();
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        Arguments.of("schemaVersion", 0),
        Arguments.of("schemaVersion", 2),
        Arguments.of("eventId", null),
        Arguments.of("orderId", null),
        Arguments.of("productId", null),
        Arguments.of("occurredAt", null),
        Arguments.of("status", null),
        Arguments.of("status", "UNKNOWN"),
        Arguments.of("quantity", 0),
        Arguments.of("quantity", -1),
        Arguments.of("quantity", 10_001),
        Arguments.of("revision", 0),
        Arguments.of("revision", -1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "{", "", "{\"eventId\":\"not-a-uuid\"}"})
  void malformedPayloadCannotCreateReceipt(String payload) {
    assertThatThrownBy(() -> consumer.accept(payload)).isInstanceOf(IllegalArgumentException.class);
    assertThat(receipts.count()).isZero();
  }

  @Test
  void duplicateWithDifferentWhitespacePreservesFirstReceiptAndTimestamp() throws Exception {
    var event = event();
    var payload = json.writeValueAsString(event);
    consumer.accept(payload);
    var received =
        jdbc.queryForObject(
            "SELECT received_at FROM processed_order_events WHERE id = ?",
            java.sql.Timestamp.class,
            event.eventId());
    clock.advance(Duration.ofHours(1));
    consumer.accept(json.writerWithDefaultPrettyPrinter().writeValueAsString(event));
    assertThat(receipts.count()).isEqualTo(1);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(payload);
    assertThat(
            jdbc.queryForObject(
                "SELECT received_at FROM processed_order_events WHERE id = ?",
                java.sql.Timestamp.class,
                event.eventId()))
        .isEqualTo(received);
  }

  @Test
  void rolledBackReceiptCanBeDeliveredAgain() throws Exception {
    var event = event();
    var payload = json.writeValueAsString(event);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            transaction -> {
              consumer.accept(payload);
              assertThat(receipts.findById(event.eventId()).orElseThrow().payload())
                  .isEqualTo(payload);
              transaction.setRollbackOnly();
            });

    assertThat(receipts.count()).isZero();
    clock.advance(Duration.ofSeconds(5));
    consumer.accept(payload);

    assertThat(receipts.count()).isEqualTo(1);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(payload);
    assertThat(
            jdbc.queryForObject(
                "SELECT received_at FROM processed_order_events WHERE id = ?",
                java.sql.Timestamp.class,
                event.eventId()))
        .isEqualTo(java.sql.Timestamp.from(clock.instant()));
  }

  @Test
  void unrelatedConstraintFailureIsPropagatedAndDeliveryCanBeRetried() throws Exception {
    var event = event();
    var payload = json.writeValueAsString(event);
    jdbc.execute(
        "ALTER TABLE processed_order_events ADD CONSTRAINT reject_reserved_receipt"
            + " CHECK (event_type <> 'RESERVED')");
    try {
      assertThatThrownBy(() -> consumer.accept(payload))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(receipts.count()).isZero();
    } finally {
      jdbc.execute("ALTER TABLE processed_order_events DROP CONSTRAINT reject_reserved_receipt");
    }

    consumer.accept(payload);
    assertThat(receipts.count()).isEqualTo(1);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(payload);
  }

  @Test
  void unreadableStoredReceiptIsReportedWithoutReplacingIt() throws Exception {
    var event = event();
    var payload = json.writeValueAsString(event);
    consumer.accept(payload);
    jdbc.update("UPDATE processed_order_events SET payload = ? WHERE id = ?", "{", event.eventId());
    clock.advance(Duration.ofHours(1));

    assertThatThrownBy(() -> consumer.accept(payload))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Corrupted event receipt")
        .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    assertThat(receipts.count()).isEqualTo(1);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo("{");
    assertThat(
            jdbc.queryForObject(
                "SELECT received_at FROM processed_order_events WHERE id = ?",
                java.sql.Timestamp.class,
                event.eventId()))
        .isEqualTo(java.sql.Timestamp.from(START));
  }

  @ParameterizedTest
  @ValueSource(strings = {"orderId", "productId", "quantity", "revision", "occurredAt"})
  void conflictingDuplicateDoesNotOverwriteOriginalReceipt(String field) throws Exception {
    var event = event();
    var original = json.writeValueAsString(event);
    consumer.accept(original);
    ObjectNode changed = json.valueToTree(event);
    switch (field) {
      case "orderId", "productId" -> changed.put(field, UUID.randomUUID().toString());
      case "quantity", "revision" -> changed.put(field, 2);
      case "occurredAt" -> changed.set(field, json.valueToTree(START.plusSeconds(1)));
      default -> throw new AssertionError(field);
    }
    assertThatThrownBy(() -> consumer.accept(json.writeValueAsString(changed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Event ID reused with different content");
    assertThat(receipts.count()).isEqualTo(1);
    assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(original);
    consumer.accept(original);
    assertThat(receipts.count()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void concurrentDuplicateCannotReplaceCommittedReceipt(boolean conflicting) throws Exception {
    var event = event();
    var original = json.writeValueAsString(event);
    var incoming =
        conflicting
            ? original.replace("RESERVED", "CONFIRMED")
            : json.writerWithDefaultPrettyPrinter().writeValueAsString(event);
    var interleaved = mock(ProcessedEventRepository.class, delegatesTo(receipts));
    var firstLookup = new AtomicBoolean(true);
    try (var worker = Executors.newSingleThreadExecutor()) {
      doAnswer(
              invocation -> {
                var found = receipts.findById(event.eventId());
                if (firstLookup.getAndSet(false)) {
                  assertThat(found).isEmpty();
                  // Another transaction commits after our lookup but before our write.
                  worker.submit(() -> consumer.accept(original)).get(10, TimeUnit.SECONDS);
                  clock.advance(Duration.ofHours(1));
                }
                return found;
              })
          .when(interleaved)
          .findById(event.eventId());
      var competing = new OrderEventConsumer(interleaved, json, clock, transactions);
      var failure = catchThrowable(() -> competing.accept(incoming));

      assertThat(receipts.count()).isEqualTo(1);
      assertThat(receipts.findById(event.eventId()).orElseThrow().payload()).isEqualTo(original);
      assertThat(
              jdbc.queryForObject(
                  "SELECT received_at FROM processed_order_events WHERE id = ?",
                  java.sql.Timestamp.class,
                  event.eventId()))
          .isEqualTo(java.sql.Timestamp.from(START));
      if (conflicting) {
        assertThat(failure)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Event ID reused with different content");
      } else {
        assertThat(failure).isNull();
      }
    }
  }
}
