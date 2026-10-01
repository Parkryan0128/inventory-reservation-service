package dev.inventory.order;

import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class ReservationExpiry {
  private final ReservationRepository orders;
  private final OrderService service;
  private final Clock clock;

  public ReservationExpiry(ReservationRepository orders, OrderService service, Clock clock) {
    this.orders = orders;
    this.service = service;
    this.clock = clock;
  }

  public int expireDue() {
    int expired = 0;
    for (var order :
        orders.findTop50ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            OrderStatus.RESERVED, clock.instant())) {
      if (service.expire(order.id())) expired++;
    }
    return expired;
  }
}
