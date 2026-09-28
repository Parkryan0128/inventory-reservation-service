package dev.ryanpark.reservation.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderEventConsumer {
    private final ProcessedEventRepository receipts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public OrderEventConsumer(ProcessedEventRepository receipts, ObjectMapper mapper, Clock clock, PlatformTransactionManager manager) {
        this.receipts = receipts; this.mapper = mapper; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }
    public void accept(String payload) {
        final OrderEvent event;
        try { event = mapper.readValue(payload, OrderEvent.class); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid order event JSON", e); }
        if (event == null) throw new IllegalArgumentException("Missing order event");
        event.validate();
        try {
            transaction.executeWithoutResult(status -> {
                var existing = receipts.findById(event.eventId());
                if (existing.isPresent()) { verifySame(existing.get().payload(), event); return; }
                // This receipt is the demo consumer's durable side effect; commit it before acknowledging Kafka.
                receipts.saveAndFlush(new ProcessedOrderEvent(event, payload, clock.instant()));
            });
        } catch (DataIntegrityViolationException collision) {
            var existing = receipts.findById(event.eventId());
            if (existing.isEmpty()) throw collision;
            verifySame(existing.get().payload(), event);
        }
    }
    private void verifySame(String stored, OrderEvent incoming) {
        try {
            if (!mapper.readValue(stored, OrderEvent.class).equals(incoming))
                throw new IllegalArgumentException("Event ID reused with different content");
        } catch (JsonProcessingException e) { throw new IllegalStateException("Corrupted event receipt", e); }
    }
}
