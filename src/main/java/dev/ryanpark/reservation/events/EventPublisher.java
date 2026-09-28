package dev.ryanpark.reservation.events;

import java.util.UUID;

@FunctionalInterface
public interface EventPublisher {
  void publish(UUID orderId, String payload) throws Exception;
}
