package dev.ryanpark.reservation.events;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByOrderIdOrderByRevisionAsc(UUID orderId);
    List<OutboxEvent> findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(Instant now);
    long countByPublishedAtIsNull();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> lockById(UUID id);
}
