package dev.ryanpark.reservation.admin;

import dev.ryanpark.reservation.events.OutboxRepository;
import dev.ryanpark.reservation.events.ProcessedEventRepository;
import dev.ryanpark.reservation.order.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin")
public class OperationsController {
    private final ReservationRepository orders;
    private final OutboxRepository outbox;
    private final ProcessedEventRepository receipts;
    private final boolean eventsEnabled;
    public OperationsController(ReservationRepository orders, OutboxRepository outbox, ProcessedEventRepository receipts,
                                @Value("${app.events.enabled:false}") boolean eventsEnabled) {
        this.orders = orders; this.outbox = outbox; this.receipts = receipts; this.eventsEnabled = eventsEnabled;
    }
    @GetMapping("/orders") @Transactional(readOnly = true)
    List<OrderDtos.OrderView> orders() {
        return orders.findAll(PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"))).stream().map(OrderDtos.OrderView::from).toList();
    }
    @GetMapping("/status") @Transactional(readOnly = true)
    Map<String, Object> status() {
        return Map.of("eventsEnabled", eventsEnabled, "pendingEvents", outbox.countByPublishedAtIsNull(),
                "auditReceipts", receipts.count(), "orders", orders.count());
    }
}
