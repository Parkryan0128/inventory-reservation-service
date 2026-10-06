package dev.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
  private final AtomicReference<Instant> now;
  private final ZoneId zone;

  public MutableClock(Instant now) {
    this(new AtomicReference<>(now), ZoneOffset.UTC);
  }

  private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
    this.now = now;
    this.zone = zone;
  }

  public void set(Instant instant) {
    now.set(instant);
  }

  public void advance(Duration duration) {
    now.updateAndGet(value -> value.plus(duration));
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return new MutableClock(now, zone);
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
