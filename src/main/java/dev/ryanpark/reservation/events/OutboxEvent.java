package dev.ryanpark.reservation.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
  @Id private UUID id;
  private UUID orderId;

  @Column(length = 40)
  private String eventType;

  private int revision;

  @Column(columnDefinition = "text")
  private String payload;

  private Instant createdAt;
  private Instant publishedAt;
  private Instant nextAttemptAt;
  private int attempts;

  @Column(length = 160)
  private String lastError;

  protected OutboxEvent() {}

  OutboxEvent(OrderEvent event, String payload) {
    this.id = event.eventId();
    this.orderId = event.orderId();
    this.eventType = event.status().name();
    this.revision = event.revision();
    this.payload = payload;
    this.createdAt = event.occurredAt();
    this.nextAttemptAt = event.occurredAt();
  }

  void published(Instant now) {
    publishedAt = now;
    attempts++;
    lastError = null;
  }

  void failed(Instant now, Exception error) {
    attempts = Math.min(attempts + 1, 1_000_000);
    nextAttemptAt = now.plusSeconds(Math.min(60, 1L << Math.min(attempts, 6)));
    lastError = error.getClass().getSimpleName();
  }

  public UUID id() {
    return id;
  }

  public UUID orderId() {
    return orderId;
  }

  public String eventType() {
    return eventType;
  }

  public int revision() {
    return revision;
  }

  public String payload() {
    return payload;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant publishedAt() {
    return publishedAt;
  }

  public Instant nextAttemptAt() {
    return nextAttemptAt;
  }

  public int attempts() {
    return attempts;
  }

  public String lastError() {
    return lastError;
  }
}
