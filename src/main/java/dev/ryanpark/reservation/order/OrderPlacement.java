package dev.ryanpark.reservation.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import static dev.ryanpark.reservation.order.OrderDtos.*;

/** Uniqueness-race recovery must happen outside the rolled-back write transaction. */
@Service
public class OrderPlacement {
    private final OrderService service;
    private final ReservationRepository orders;
    public OrderPlacement(OrderService service, ReservationRepository orders) { this.service = service; this.orders = orders; }
    public OrderView place(String owner, String key, ReserveRequest request) {
        OrderService.validate(owner, key, request);
        try { return service.reserve(owner, key, request); }
        catch (DataIntegrityViolationException collision) {
            var existing = orders.findByOwnerIdAndIdempotencyKey(owner, key);
            if (existing.isEmpty()) throw collision;
            return OrderService.replay(existing.get(), request);
        }
    }
}
