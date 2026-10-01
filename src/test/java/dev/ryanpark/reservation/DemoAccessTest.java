package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.*;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.demo.DemoAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DemoAccessTest {
  @Test
  void scenarioCooldownIsGlobalAndExpiresWithoutAWaitingThread() {
    var clock = new MutableClock();
    var access = new DemoAccess("inventory.example.com", true, Duration.ofSeconds(10), clock);
    access.requireScenarioSlot();
    assertThatThrownBy(access::requireScenarioSlot)
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("DEMO_RATE_LIMITED"));
    clock.now = clock.now.plusSeconds(10);
    access.requireScenarioSlot();
  }

  @Test
  void localModeHasNoCooldownAndHostComparisonIsCaseInsensitive() {
    var access = new DemoAccess("localhost,127.0.0.1", false, Duration.ZERO, Clock.systemUTC());
    var request = new MockHttpServletRequest();
    request.setServerName("LOCALHOST");
    access.requireHost(request);
    access.requireScenarioSlot();
    access.requireScenarioSlot();
    request.setServerName("unrelated.example");
    assertThatThrownBy(() -> access.requireHost(request))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code()).isEqualTo("LOCAL_DEMO_ONLY"));
  }

  private static class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
