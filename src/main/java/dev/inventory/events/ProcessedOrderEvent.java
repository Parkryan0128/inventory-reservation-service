package dev.inventory.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_order_events")
public class ProcessedOrderEvent {
  @Id private UUID id;
  private UUID orderId;

  @Column(length = 40)
  private String eventType;

  @Column(columnDefinition = "text")
  private String payload;

  private Instant receivedAt;

  protected ProcessedOrderEvent() {}

  public String payload() {
    return payload;
  }
}
