package dev.ryanpark.reservation.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ryanpark.reservation.order.Reservation;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRecorder {
  private final OutboxRepository outbox;
  private final ObjectMapper mapper;
  private final Clock clock;

  public OutboxRecorder(OutboxRepository outbox, ObjectMapper mapper, Clock clock) {
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void record(Reservation order) {
    var event =
        new OrderEvent(
            1,
            UUID.randomUUID(),
            order.id(),
            order.productId(),
            order.quantity(),
            order.status(),
            order.revision(),
            clock.instant());
    try {
      outbox.save(new OutboxEvent(event, mapper.writeValueAsString(event)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize order event", e);
    }
  }
}
