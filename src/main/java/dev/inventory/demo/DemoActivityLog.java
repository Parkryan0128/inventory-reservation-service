package dev.inventory.demo;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DemoActivityLog {
  private final Clock clock;
  private final List<Entry> entries = new ArrayList<>();

  public DemoActivityLog(Clock clock) {
    this.clock = clock;
  }

  public synchronized void record(
      String requestId,
      String actor,
      String operation,
      String code,
      UUID orderId,
      int quantity,
      String key,
      long durationMs) {
    entries.add(
        new Entry(
            entries.size() + 1,
            clock.instant().toString(),
            requestId,
            actor,
            operation,
            code,
            orderId,
            quantity,
            key,
            durationMs,
            null));
  }

  public synchronized void snapshot(int index) {
    entries.add(
        new Entry(
            entries.size() + 1,
            clock.instant().toString(),
            "",
            "database",
            "inventory",
            "SNAPSHOT",
            null,
            0,
            "",
            0,
            index));
  }

  public synchronized List<Entry> entries() {
    return List.copyOf(entries);
  }

  public record Entry(
      int sequence,
      String recordedAt,
      String requestId,
      String actor,
      String operation,
      String code,
      UUID orderId,
      int quantity,
      String key,
      long durationMs,
      Integer snapshotIndex) {}
}
