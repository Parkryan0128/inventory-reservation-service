package dev.inventory.order;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
public class ReservationExpiry {
  private static final Logger log = LoggerFactory.getLogger(ReservationExpiry.class);
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
      try {
        if (service.expire(order.id())) expired++;
      } catch (PessimisticLockingFailureException busy) {
        log.warn("Reservation {} is locked; retrying on the next expiry pass", order.id());
      }
    }
    return expired;
  }
}
