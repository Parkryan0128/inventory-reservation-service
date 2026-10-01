package dev.inventory.events;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedOrderEvent, UUID> {
  long countByOrderId(UUID orderId);
}
