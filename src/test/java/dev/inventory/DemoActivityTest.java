package dev.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import dev.inventory.demo.DemoService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"demo", "test"})
@AutoConfigureMockMvc
class DemoActivityTest {
  @Autowired DemoService demo;
  @Autowired JdbcTemplate jdbc;

  @Test
  void activityMatchesReturnedCallsAndPersistedOrdersForEveryScenario() throws Exception {
    for (var scenario : DemoService.SCENARIOS) {
      var result = demo.run(scenario);
      assertThat(result.passed()).isTrue();
      var productId = result.snapshots().getFirst().inventory().id();
      var persisted =
          jdbc.queryForList(
              "SELECT id FROM reservations WHERE product_id = ?", UUID.class, productId);
      int snapshot = 0;
      int requestCount = 0;
      for (int index = 0; index < result.activity().size(); index++) {
        var entry = result.activity().get(index);
        assertThat(entry.sequence()).isEqualTo(index + 1);
        assertThat(Instant.parse(entry.recordedAt())).isNotNull();
        assertThat(entry.durationMs()).isNotNegative();
        if (entry.orderId() != null) assertThat(persisted).contains(entry.orderId());
        if (entry.snapshotIndex() != null) {
          assertThat(entry.snapshotIndex()).isEqualTo(snapshot++);
          assertThat(entry.operation()).isEqualTo("inventory");
          assertThat(entry.code()).isEqualTo("SNAPSHOT");
        }
        if (!result.attempts().isEmpty() && entry.requestId().startsWith("req-")) {
          var attempt = result.attempts().get(Integer.parseInt(entry.requestId().substring(4)) - 1);
          assertThat(entry.code()).isEqualTo(attempt.code());
          assertThat(entry.orderId()).isEqualTo(attempt.orderId());
          assertThat(entry.durationMs()).isEqualTo(attempt.durationMs());
          requestCount++;
        }
      }
      assertThat(snapshot).isEqualTo(result.snapshots().size());
      assertThat(requestCount).isEqualTo(result.attempts().size());
      assertThat(result.activity().getLast().snapshotIndex()).isEqualTo(snapshot - 1);
      assertThat(result.persistedOrders()).isEqualTo(persisted.size());
    }
  }

  @Test
  void contentionRecordsOneOutcomeForEachCustomer() throws Exception {
    var result = demo.run("contention");
    var requests = result.activity().stream().filter(e -> e.operation().equals("reserve")).toList();
    assertThat(requests).hasSize(100);
    assertThat(requests.stream().map(e -> e.requestId())).doesNotHaveDuplicates();
    assertThat(requests.stream().map(e -> e.actor())).doesNotHaveDuplicates();
    assertThat(requests.stream().filter(e -> e.code().equals("RESERVED"))).hasSize(5);
    assertThat(requests.stream().filter(e -> e.code().equals("INSUFFICIENT_STOCK"))).hasSize(95);
    assertThat(requests).allSatisfy(e -> assertThat(e.quantity()).isEqualTo(1));
  }

  @Test
  void repeatedOperationsAndExpiryNoOpsAreVisibleInActivity() throws Exception {
    var lifecycle = demo.run("lifecycle");
    assertThat(lifecycle.activity().stream().filter(e -> e.snapshotIndex() == null)).hasSize(9);
    for (var code : List.of("CONFIRMED", "CANCELLED", "PAYMENT_FAILED")) {
      var entries = lifecycle.activity().stream().filter(e -> e.code().equals(code)).toList();
      assertThat(entries).hasSize(2);
      assertThat(entries.getFirst().orderId()).isEqualTo(entries.getLast().orderId());
    }
    var expiry = demo.run("expiry");
    assertThat(
            expiry.activity().stream()
                .filter(e -> e.operation().equals("expire"))
                .map(e -> e.code()))
        .containsExactly("NO_CHANGE", "EXPIRED", "NO_CHANGE");
    assertThat(expiry.activity().stream().filter(e -> e.operation().equals("advance-deadline")))
        .singleElement()
        .satisfies(e -> assertThat(e.code()).isEqualTo("DEADLINE_ADVANCED"));
  }

  @Test
  void repeatedRunsDoNotShareRecordings() throws Exception {
    var first = demo.run("race");
    var second = demo.run("race");
    assertThat(first.activity()).hasSize(5);
    assertThat(second.activity()).hasSize(5);
    assertThat(second.activity().getFirst().sequence()).isEqualTo(1);
    assertThat(first.activity().getFirst().orderId())
        .isNotEqualTo(second.activity().getFirst().orderId());
  }
}
