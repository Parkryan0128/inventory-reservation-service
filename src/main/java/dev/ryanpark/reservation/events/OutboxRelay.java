package dev.ryanpark.reservation.events;

import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final ObjectProvider<EventPublisher> publishers;
    private final Clock clock;
    public OutboxRelay(OutboxRepository outbox, ObjectProvider<EventPublisher> publishers, Clock clock) {
        this.outbox = outbox; this.publishers = publishers; this.clock = clock;
    }
    @Transactional
    public boolean publish(UUID id) {
        var publisher = publishers.getIfAvailable();
        if (publisher == null) return false;
        var event = outbox.lockById(id).orElseThrow();
        if (event.publishedAt() != null || clock.instant().isBefore(event.nextAttemptAt())) return false;
        try {
            // Holding the row lock is simple but intentionally trades throughput for a bounded network wait.
            publisher.publish(event.orderId(), event.payload());
            event.published(clock.instant());
            return true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            event.failed(clock.instant(), failure);
            return false;
        }
    }
}
