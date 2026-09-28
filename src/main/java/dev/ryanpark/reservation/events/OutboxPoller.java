package dev.ryanpark.reservation.events;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class OutboxPoller {
  private final OutboxRepository outbox;
  private final OutboxRelay relay;
  private final Clock clock;

  public OutboxPoller(OutboxRepository outbox, OutboxRelay relay, Clock clock) {
    this.outbox = outbox;
    this.relay = relay;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${app.outbox-interval-ms:1000}")
  public void poll() {
    for (var event :
        outbox.findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            clock.instant())) relay.publish(event.id());
  }
}
