package dev.ryanpark.reservation.order;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.ryanpark.reservation.order.OrderDtos.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    private final OrderPlacement placement;
    public OrderController(OrderService service, OrderPlacement placement) { this.service = service; this.placement = placement; }
    @PostMapping
    ResponseEntity<OrderView> reserve(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner,
                                     @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody ReserveRequest request) {
        var order = placement.place(owner, key, request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }
    @GetMapping("/{id}")
    OrderView get(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner, @PathVariable UUID id) {
        return service.get(owner, id);
    }
    @GetMapping
    List<OrderView> list(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner,
                         @RequestParam(defaultValue = "0") int page) { return service.list(owner, page); }
    @PostMapping("/{id}/cancel")
    OrderView cancel(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner, @PathVariable UUID id) {
        return service.cancel(owner, id);
    }
}
