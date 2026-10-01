package dev.inventory.events;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class OutboxPoller {
  private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
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
            clock.instant())) {
      try {
        relay.publish(event.id());
      } catch (PessimisticLockingFailureException busy) {
        log.warn("Outbox event {} is locked; retrying on the next relay pass", event.id());
      }
    }
  }
}
