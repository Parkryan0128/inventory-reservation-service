package dev.ryanpark.reservation.order;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static dev.ryanpark.reservation.order.OrderDtos.*;

@RestController
@RequestMapping("/api/admin/payments")
public class PaymentSimulatorController {
    private final OrderService orders;
    public PaymentSimulatorController(OrderService orders) { this.orders = orders; }
    @PostMapping("/{orderId}")
    OrderView simulate(@PathVariable UUID orderId, @Valid @RequestBody PaymentRequest request) {
        return orders.payment(orderId, request.success());
    }
}
