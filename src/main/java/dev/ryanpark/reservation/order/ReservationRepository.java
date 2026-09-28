package dev.ryanpark.reservation.order;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    long countByProductId(UUID productId);
}
