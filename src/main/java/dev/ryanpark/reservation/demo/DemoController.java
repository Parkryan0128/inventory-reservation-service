package dev.ryanpark.reservation.demo;

import dev.ryanpark.reservation.common.ApiException;
import dev.ryanpark.reservation.events.OutboxRepository;
import dev.ryanpark.reservation.events.ProcessedEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
@Profile("demo")
public class DemoController {
  private final DemoService demo;
  private final OutboxRepository outbox;
  private final ProcessedEventRepository receipts;
  private final JdbcTemplate jdbc;
  private final boolean eventsEnabled;

  public DemoController(
      DemoService demo,
      OutboxRepository outbox,
      ProcessedEventRepository receipts,
      JdbcTemplate jdbc,
      @Value("${app.events.enabled:false}") boolean eventsEnabled) {
    this.demo = demo;
    this.outbox = outbox;
    this.receipts = receipts;
    this.jdbc = jdbc;
    this.eventsEnabled = eventsEnabled;
  }

  @GetMapping("/")
  public ModelAndView home() {
    return new ModelAndView("forward:/demo.html");
  }

  @GetMapping("/api/demo/status")
  public Map<String, Object> status(HttpServletRequest request) {
    requireLocal(request);
    var database =
        jdbc.execute(
            (ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
    return Map.of(
        "busy",
        demo.busy(),
        "scenarios",
        DemoService.SCENARIOS,
        "database",
        database,
        "eventsEnabled",
        eventsEnabled,
        "pendingEvents",
        outbox.countByPublishedAtIsNull(),
        "auditReceipts",
        receipts.count());
  }

  @PostMapping("/api/demo/run/{scenario}")
  public DemoService.Result run(@PathVariable String scenario, HttpServletRequest request)
      throws Exception {
    requireLocal(request);
    return demo.run(scenario);
  }

  private void requireLocal(HttpServletRequest request) {
    if (!Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(request.getServerName()))
      throw new ApiException(HttpStatus.FORBIDDEN, "LOCAL_DEMO_ONLY", "Use localhost for the demo");
  }
}
