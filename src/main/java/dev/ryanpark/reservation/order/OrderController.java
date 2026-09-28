package dev.ryanpark.reservation.order;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static dev.ryanpark.reservation.order.OrderDtos.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    ResponseEntity<OrderView> reserve(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner,
                                     @Valid @RequestBody ReserveRequest request) {
        var order = service.reserve(owner, request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    OrderView get(@RequestHeader(value = "X-Customer-Id", defaultValue = "demo") String owner, @PathVariable UUID id) {
        return service.get(owner, id);
    }
}
