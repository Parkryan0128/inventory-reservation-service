package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import dev.inventory.events.OutboxEvent;
import dev.inventory.events.OutboxPoller;
import dev.inventory.events.OutboxRelay;
import dev.inventory.events.OutboxRepository;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import dev.inventory.order.Reservation;
import dev.inventory.order.ReservationExpiry;
import dev.inventory.order.ReservationRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

class ScheduledBatchTest {
  @Test
  void lockedReservationDoesNotBlockOtherOrdersAndIsRetriedNextPass() {
    var repository = mock(ReservationRepository.class);
    var service = mock(OrderService.class);
    var first = mock(Reservation.class);
    var second = mock(Reservation.class);
    when(first.id()).thenReturn(UUID.randomUUID());
    when(second.id()).thenReturn(UUID.randomUUID());
    when(repository.findTop50ByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            eq(OrderStatus.RESERVED), any()))
        .thenReturn(List.of(first, second));
    when(service.expire(first.id()))
        .thenThrow(new CannotAcquireLockException("lock timeout"))
        .thenReturn(true);
    when(service.expire(second.id())).thenReturn(true, false);
    var expiry = new ReservationExpiry(repository, service, Clock.systemUTC());

    assertThat(expiry.expireDue()).isEqualTo(1);
    verify(service).expire(second.id());
    assertThat(expiry.expireDue()).isEqualTo(1);
    verify(service, times(2)).expire(first.id());

    when(service.expire(first.id())).thenThrow(new IllegalStateException("unexpected failure"));
    assertThatThrownBy(expiry::expireDue).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void lockedOutboxRowDoesNotBlockOtherEventsAndIsRetriedNextPass() {
    var repository = mock(OutboxRepository.class);
    var relay = mock(OutboxRelay.class);
    var first = mock(OutboxEvent.class);
    var second = mock(OutboxEvent.class);
    when(first.id()).thenReturn(UUID.randomUUID());
    when(second.id()).thenReturn(UUID.randomUUID());
    when(repository.findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            any()))
        .thenReturn(List.of(first, second));
    when(relay.publish(first.id()))
        .thenThrow(new CannotAcquireLockException("lock timeout"))
        .thenReturn(true);
    var poller = new OutboxPoller(repository, relay, Clock.systemUTC());

    poller.poll();
    verify(relay).publish(second.id());
    poller.poll();
    verify(relay, times(2)).publish(first.id());

    when(relay.publish(first.id())).thenThrow(new IllegalStateException("unexpected failure"));
    assertThatThrownBy(poller::poll).isInstanceOf(IllegalStateException.class);
  }
}
