package dev.inventory.order;

import static dev.inventory.order.OrderDtos.OrderView;
import static dev.inventory.order.OrderDtos.ReserveRequest;

import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
  private final OrderService service;
  private final OrderPlacement placement;

  public OrderController(OrderService service, OrderPlacement placement) {
    this.service = service;
    this.placement = placement;
  }

  @PostMapping
  ResponseEntity<OrderView> reserve(
      Principal principal,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody ReserveRequest request) {
    var order = placement.place(principal.getName(), key, request);
    return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
  }

  @GetMapping("/{id}")
  OrderView get(Principal principal, @PathVariable UUID id) {
    return service.get(principal.getName(), id);
  }

  @GetMapping
  List<OrderView> list(Principal principal, @RequestParam(defaultValue = "0") int page) {
    return service.list(principal.getName(), page);
  }

  @PostMapping("/{id}/cancel")
  OrderView cancel(Principal principal, @PathVariable UUID id) {
    return service.cancel(principal.getName(), id);
  }
}
