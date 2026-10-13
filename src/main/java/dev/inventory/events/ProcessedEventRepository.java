package dev.inventory.events;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProcessedEventRepository extends JpaRepository<ProcessedOrderEvent, UUID> {
  long countByOrderId(UUID orderId);

  @Modifying
  @Query(
      value =
          """
      INSERT INTO processed_order_events (id, order_id, event_type, payload, received_at)
      VALUES (:id, :orderId, :eventType, :payload, :receivedAt)
      """,
      nativeQuery = true)
  void insertReceipt(UUID id, UUID orderId, String eventType, String payload, Instant receivedAt);
}
