package dev.ryanpark.reservation.cache;

import java.time.Duration;

public interface MetadataCache {
  String get(String key);

  void put(String key, String value, Duration ttl);
}
