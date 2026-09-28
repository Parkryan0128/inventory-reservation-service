package dev.ryanpark.reservation.cache;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true")
public class RedisMetadataCache implements MetadataCache {
  private final StringRedisTemplate redis;

  public RedisMetadataCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public String get(String key) {
    return redis.opsForValue().get(key);
  }

  public void put(String key, String value, Duration ttl) {
    redis.opsForValue().set(key, value, ttl);
  }
}
