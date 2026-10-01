package dev.inventory.order;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
  long countByProductId(UUID productId);

  Optional<Reservation> findByOwnerIdAndIdempotencyKey(String owner, String key);

  List<Reservation> findByOwnerIdOrderByCreatedAtDesc(String owner, Pageable pageable);

  List<Reservation> findTop50ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
      OrderStatus status, Instant cutoff);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Reservation r where r.id = :id")
  Optional<Reservation> lockById(UUID id);
}
