package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.inventory.common.ApiException;
import dev.inventory.demo.DemoService;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.order.OrderDtos.ReserveRequest;
import dev.inventory.order.OrderService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles({"demo", "test"})
@AutoConfigureMockMvc
class DemoScenariosTest {
  @Autowired DemoService demo;
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void contentionRejectsOnlyExcessAndRunsUseFreshProducts() throws Exception {
    var first = demo.run("contention");
    var second = demo.run("contention");
    for (var result : List.of(first, second)) {
      assertThat(result.passed()).isTrue();
      assertThat(result.outcomes())
          .containsOnly(entry("RESERVED", 5L), entry("INSUFFICIENT_STOCK", 95L));
      var last = result.snapshots().getLast().inventory();
      assertThat(List.of(last.available(), last.reserved(), last.sold())).containsExactly(0, 5, 0);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, last.id()))
          .isEqualTo(5);
    }
    assertThat(first.snapshots().getFirst().inventory().id())
        .isNotEqualTo(second.snapshots().getFirst().inventory().id());
  }

  @Test
  void concurrentReplayUsesOneOrderAndChangedPayloadCannotMutateIt() throws Exception {
    var result = demo.run("idempotency");
    assertThat(result.passed()).isTrue();
    assertThat(result.outcomes())
        .containsOnly(entry("RESERVED", 16L), entry("IDEMPOTENCY_CONFLICT", 1L));
    var last = result.snapshots().getLast().inventory();
    assertThat(List.of(last.available(), last.reserved(), last.sold())).containsExactly(7, 3, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE product_id = ?", Long.class, last.id()))
        .isEqualTo(1);
    assertThat(result.snapshots().get(1).inventory()).isEqualTo(last);
  }

  @Test
  void lifecyclePersistsEachTerminalStateWithoutDoubleStockChanges() throws Exception {
    var result = demo.run("lifecycle");
    assertThat(result.passed()).isTrue();
    var last = result.snapshots().getLast().inventory();
    assertThat(List.of(last.available(), last.reserved(), last.sold())).containsExactly(9, 0, 3);
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM reservations WHERE product_id = ?", String.class, last.id()))
        .containsExactlyInAnyOrder("CONFIRMED", "CANCELLED", "PAYMENT_FAILED");
    assertThat(result.snapshots()).hasSize(7);
  }

  @RepeatedTest(5)
  void racingTransitionsHaveOneWinnerAndOneConflict() throws Exception {
    var result = demo.run("race");
    assertThat(result.passed()).isTrue();
    assertThat(result.outcomes().get("INVALID_TRANSITION")).isEqualTo(1);
    assertThat(
            result.outcomes().getOrDefault("CONFIRMED", 0L)
                + result.outcomes().getOrDefault("CANCELLED", 0L))
        .isEqualTo(1);
    var last = result.snapshots().getLast().inventory();
    assertThat(last.reserved()).isZero();
    assertThat(last.available() + last.sold()).isEqualTo(1);
  }

  @Test
  void expiryAdvancesOnlyTheGeneratedOrderDeadline() throws Exception {
    var unrelated =
        catalog.create(
            new CreateProduct(
                "KEEP-" + UUID.randomUUID().toString().toUpperCase(),
                "Existing product",
                100,
                "CAD",
                8));
    var order = orders.reserve("alice", new ReserveRequest(unrelated.id(), 2));
    var baseline = orders.get("alice", order.id());
    var inventory = catalog.get(unrelated.id());
    var result = demo.run("expiry");
    assertThat(result.passed()).isTrue();
    var last = result.snapshots().getLast().inventory();
    assertThat(List.of(last.available(), last.reserved(), last.sold())).containsExactly(5, 0, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM reservations WHERE product_id = ?", String.class, last.id()))
        .isEqualTo("EXPIRED");
    assertThat(orders.get("alice", order.id())).isEqualTo(baseline);
    assertThat(catalog.get(unrelated.id())).isEqualTo(inventory);
  }

  @Test
  void unknownScenariosDoNotCreateData() {
    var count = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    assertThatThrownBy(() -> demo.run("arbitrary-sql")).isInstanceOf(ApiException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isEqualTo(count);
    assertThat(demo.busy()).isFalse();
  }

  @Test
  void anonymousBrowserCanRunWithRealCsrfToken() throws Exception {
    var session = new MockHttpSession();
    var response =
        mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn();
    var token = json.readTree(response.getResponse().getContentAsString());
    mvc.perform(
            post("/api/demo/run/race")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true));
    mvc.perform(get("/api/demo/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scenarios.length()").value(5));
  }

  @Test
  void anonymousDemoStillRequiresCsrfAndDoesNotUnlockNormalApis() throws Exception {
    mvc.perform(post("/api/demo/run/contention")).andExpect(status().isForbidden());
    mvc.perform(post("/api/demo/run/contention").with(csrf().useInvalidToken()))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/admin/status")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/products").with(csrf())).andExpect(status().isUnauthorized());
  }

  @Test
  void nonLocalHostIsRejectedAndInvalidScenarioIsBadRequest() throws Exception {
    mvc.perform(get("http://rebinding.example/api/demo/status"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LOCAL_DEMO_ONLY"));
    mvc.perform(post("http://rebinding.example/api/demo/run/race").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/demo/run/unknown").with(csrf())).andExpect(status().isBadRequest());
  }

  @Test
  void homeAndAssetsAreAccessibleWithoutLogin() throws Exception {
    mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/demo.html"));
    for (var path : List.of("/demo.html", "/demo.js", "/demo-client.js", "/demo.css")) {
      mvc.perform(get(path)).andExpect(status().isOk());
    }
  }

  @Test
  void requestRecordsMatchOutcomesAndActualDatabaseRows() throws Exception {
    for (var scenario : List.of("contention", "idempotency", "race")) {
      var result = demo.run(scenario);
      assertThat(result.passed()).isTrue();
      var expectedSize =
          switch (scenario) {
            case "contention" -> 100;
            case "idempotency" -> 17;
            default -> 2;
          };
      assertThat(result.attempts()).hasSize(expectedSize);
      assertThat(result.attempts())
          .allSatisfy(attempt -> assertThat(attempt.durationMs()).isNotNegative());
      var histogram =
          result.attempts().stream()
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      DemoService.Attempt::code, java.util.stream.Collectors.counting()));
      assertThat(histogram).isEqualTo(result.outcomes());
      var productId = result.snapshots().getLast().inventory().id();
      var persisted =
          jdbc.queryForList(
              "SELECT id FROM reservations WHERE product_id = ?", UUID.class, productId);
      assertThat(result.persistedOrders()).isEqualTo(persisted.size());
      var returned =
          result.attempts().stream()
              .map(DemoService.Attempt::orderId)
              .filter(java.util.Objects::nonNull)
              .distinct()
              .toList();
      assertThat(returned).containsExactlyInAnyOrderElementsOf(persisted);
      assertThat(java.time.Instant.parse(result.completedAt())).isNotNull();
      if (scenario.equals("contention")) {
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT owner_id) FROM reservations WHERE product_id = ?",
                    Long.class,
                    productId))
            .isEqualTo(5);
      }
      if (scenario.equals("race")) {
        assertThat(result.attempts().get(0).code()).isIn("CONFIRMED", "INVALID_TRANSITION");
        assertThat(result.attempts().get(1).code()).isIn("CANCELLED", "INVALID_TRANSITION");
      }
    }
  }

  @Test
  void sequentialScenariosExposeSnapshotsWithoutInventedRequestRecords() throws Exception {
    for (var scenario : List.of("lifecycle", "expiry")) {
      var result = demo.run(scenario);
      assertThat(result.attempts()).isEmpty();
      assertThat(result.persistedOrders()).isEqualTo(scenario.equals("lifecycle") ? 3 : 1);
      assertThat(result.snapshots()).isNotEmpty();
    }
  }
}
