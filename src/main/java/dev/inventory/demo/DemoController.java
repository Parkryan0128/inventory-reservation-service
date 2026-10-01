package dev.inventory.demo;

import dev.inventory.common.ApiException;
import dev.inventory.events.OutboxRepository;
import dev.inventory.events.ProcessedEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

@RestController
@Profile({"demo", "public-demo"})
public class DemoController {
  private final DemoService demo;
  private final OutboxRepository outbox;
  private final ProcessedEventRepository receipts;
  private final JdbcTemplate jdbc;
  private final boolean eventsEnabled;
  private final DemoAccess access;

  public DemoController(
      DemoService demo,
      OutboxRepository outbox,
      ProcessedEventRepository receipts,
      JdbcTemplate jdbc,
      DemoAccess access,
      @Value("${app.events.enabled:false}") boolean eventsEnabled) {
    this.demo = demo;
    this.outbox = outbox;
    this.receipts = receipts;
    this.jdbc = jdbc;
    this.eventsEnabled = eventsEnabled;
    this.access = access;
  }

  @GetMapping("/")
  public ModelAndView home() {
    return new ModelAndView("forward:/demo.html");
  }

  @GetMapping("/api/demo/status")
  public Map<String, Object> status(HttpServletRequest request) {
    access.requireHost(request);
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
    access.requireHost(request);
    if (!DemoService.SCENARIOS.contains(scenario))
      throw ApiException.invalid("Unknown demo scenario");
    access.requireScenarioSlot();
    return demo.run(scenario);
  }
}
