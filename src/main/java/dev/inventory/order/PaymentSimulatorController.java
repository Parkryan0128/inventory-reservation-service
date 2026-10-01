package dev.inventory.order;

import static dev.inventory.order.OrderDtos.OrderView;
import static dev.inventory.order.OrderDtos.PaymentRequest;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments")
public class PaymentSimulatorController {
  private final OrderService orders;

  public PaymentSimulatorController(OrderService orders) {
    this.orders = orders;
  }

  @PostMapping("/{orderId}")
  OrderView simulate(@PathVariable UUID orderId, @Valid @RequestBody PaymentRequest request) {
    return orders.payment(orderId, request.success());
  }
}
