package dev.ryanpark.reservation.common;

import dev.ryanpark.reservation.order.ReservationExpiry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTasks {
    private final ReservationExpiry expiry;
    public ScheduledTasks(ReservationExpiry expiry) { this.expiry = expiry; }
    @Scheduled(fixedDelayString = "${app.expiry-interval-ms:5000}")
    public void expireReservations() { expiry.expireDue(); }
}
