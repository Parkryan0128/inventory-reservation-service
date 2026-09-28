package dev.ryanpark.reservation.order;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    long countByProductId(UUID productId);
    Optional<Reservation> findByOwnerIdAndIdempotencyKey(String owner, String key);
    List<Reservation> findByOwnerIdOrderByCreatedAtDesc(String owner, Pageable pageable);
    List<Reservation> findTop50ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(OrderStatus status, Instant cutoff);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> lockById(UUID id);
}
