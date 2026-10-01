package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile({"demo", "public-demo"})
public class DemoAccess {
  private final Set<String> hosts;
  private final boolean publicDemo;
  private final Duration scenarioInterval;
  private final Clock clock;
  private Instant nextScenarioAt = Instant.MIN;

  public DemoAccess(
      @Value("${app.demo.allowed-hosts:localhost,127.0.0.1,::1,[::1]}") String hosts,
      @Value("${app.demo.public:false}") boolean publicDemo,
      @Value("${app.demo.scenario-interval:PT0S}") Duration scenarioInterval,
      Clock clock) {
    this.hosts =
        Arrays.stream(hosts.split(","))
            .map(String::strip)
            .map(host -> host.toLowerCase(Locale.ROOT))
            .filter(host -> !host.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    if (this.hosts.isEmpty() || scenarioInterval.isNegative()) {
      throw new IllegalArgumentException(
          "Configure demo hosts and a nonnegative scenario interval");
    }
    this.publicDemo = publicDemo;
    this.scenarioInterval = scenarioInterval;
    this.clock = clock;
  }

  public void requireHost(HttpServletRequest request) {
    if (!hosts.contains(request.getServerName().toLowerCase(Locale.ROOT))) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          publicDemo ? "DEMO_HOST_NOT_ALLOWED" : "LOCAL_DEMO_ONLY",
          publicDemo ? "Use the configured demo address" : "Use localhost for the demo");
    }
  }

  public synchronized void requireScenarioSlot() {
    var now = clock.instant();
    if (now.isBefore(nextScenarioAt)) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "DEMO_RATE_LIMITED",
          "Please wait a few seconds before starting another scenario");
    }
    nextScenarioAt = now.plus(scenarioInterval);
  }
}
