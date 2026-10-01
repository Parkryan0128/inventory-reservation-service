package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.inventory.demo.ManualActivityLog;
import dev.inventory.demo.ManualDemoService;
import dev.inventory.demo.ManualDemoService.Action;
import dev.inventory.demo.ManualDemoService.Command;
import dev.inventory.demo.ManualDemoService.State;
import dev.inventory.demo.SharedDemoProduct;
import dev.inventory.inventory.CatalogService;
import dev.inventory.inventory.ProductDtos.CreateProduct;
import dev.inventory.inventory.ProductRepository;
import dev.inventory.order.OrderService;
import dev.inventory.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles({"demo", "test"})
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.manual-action-interval-ms=0")
class ManualDemoTest {
  @Autowired ManualDemoService manual;
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired ManualActivityLog activity;
  @Autowired ProductRepository products;
  @Autowired PlatformTransactionManager transactions;

  @BeforeEach
  void resetSharedFixture() {
    var id = manual.state(manual.create()).inventory().id();
    jdbc.update(
        "DELETE FROM processed_order_events WHERE order_id IN (SELECT id FROM reservations WHERE"
            + " product_id = ?)",
        id);
    jdbc.update(
        "DELETE FROM outbox_events WHERE order_id IN (SELECT id FROM reservations WHERE product_id"
            + " = ?)",
        id);
    jdbc.update("DELETE FROM reservations WHERE product_id = ?", id);
    jdbc.update(
        "UPDATE products SET available = 5, reserved = 0, sold = 0, initial_stock = 5 WHERE id = ?",
        id);
  }

  @Test
  void removingAndAddingStockChangesRealPurchaseOutcomesOnTheSameProduct() {
    var workspace = manual.create();
    var initial = manual.state(workspace);
    var removed = manual.act(workspace, new Command(Action.REMOVE_STOCK, 5));
    assertThat(removed.statusCode()).isEqualTo(200);
    assertThat(catalog.get(initial.inventory().id()).available()).isZero();
    var rejected = manual.act(workspace, new Command(Action.BUY, 1));
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(rejected.state().result().code()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(rejected.state().order()).isNull();
    manual.act(workspace, new Command(Action.ADD_STOCK, 2));
    var bought = manual.act(workspace, new Command(Action.BUY, 1)).state();
    assertThat(bought.inventory().id()).isEqualTo(initial.inventory().id());
    assertThat(bought.inventory().available()).isEqualTo(1);
    assertThat(bought.inventory().reserved()).isEqualTo(1);
    var paid = manual.act(workspace, new Command(Action.PAY, 1)).state();
    assertThat(paid.order().status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(paid.inventory().reserved()).isZero();
    assertThat(paid.inventory().sold()).isEqualTo(1);
    assertThat(catalog.get(initial.inventory().id())).isEqualTo(paid.inventory());
    assertThat(orders.get(paid.order().ownerId(), paid.order().id())).isEqualTo(paid.order());
    var cannotRemoveSold = manual.act(workspace, new Command(Action.REMOVE_STOCK, 2));
    assertThat(cannotRemoveSold.statusCode()).isEqualTo(409);
    assertThat(cannotRemoveSold.state().inventory()).isEqualTo(paid.inventory());
    assertThat(manual.act(workspace, new Command(Action.PAY, 1)).state().inventory())
        .isEqualTo(paid.inventory());
  }

  @Test
  void heldStockCannotBeRemovedAndCancellationReturnsItOnce() {
    var workspace = manual.create();
    var held = manual.act(workspace, new Command(Action.BUY, 2)).state();
    assertThat(manual.act(workspace, new Command(Action.BUY, 1)).statusCode()).isEqualTo(409);
    var rejected = manual.act(workspace, new Command(Action.REMOVE_STOCK, 4));
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(rejected.state().inventory()).isEqualTo(held.inventory());
    manual.act(workspace, new Command(Action.REMOVE_STOCK, 3));
    var cancelled = manual.act(workspace, new Command(Action.CANCEL, 1)).state();
    assertThat(cancelled.inventory().available()).isEqualTo(2);
    assertThat(cancelled.inventory().reserved()).isZero();
    assertThat(cancelled.inventory().initialStock()).isEqualTo(2);
    assertThat(manual.act(workspace, new Command(Action.CANCEL, 1)).state().inventory())
        .isEqualTo(cancelled.inventory());
    var next = manual.act(workspace, new Command(Action.BUY, 1)).state();
    assertThat(next.order().id()).isNotEqualTo(cancelled.order().id());
    assertThat(next.inventory().id()).isEqualTo(cancelled.inventory().id());
  }

  @Test
  void refreshObservesExpiryWithoutInventingAnActionOrRepeatedLogEntries() {
    var workspace = manual.create();
    var held = manual.act(workspace, new Command(Action.BUY, 2)).state();
    jdbc.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        held.order().id());
    orders.expire(held.order().id());
    var refreshed = manual.state(workspace);
    assertThat(refreshed.order().status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(refreshed.inventory().available()).isEqualTo(5);
    assertThat(refreshed.activity().getLast().code()).isEqualTo("EXPIRED");
    assertThat(refreshed.activity().getLast().actor()).isEqualTo("System");
    assertThat(manual.state(workspace).activity()).isEqualTo(refreshed.activity());
  }

  @Test
  void paymentAfterTheDeadlineReturnsActualExpiredStateAndReleasesStock() {
    var workspace = manual.create();
    var held = manual.act(workspace, new Command(Action.BUY, 2)).state();
    jdbc.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        held.order().id());
    var result = manual.act(workspace, new Command(Action.PAY, 1));
    assertThat(result.statusCode()).isEqualTo(200);
    assertThat(result.state().order().status()).isEqualTo(OrderStatus.EXPIRED);
    assertThat(result.state().activity().getLast().code()).isEqualTo("EXPIRED");
    assertThat(result.state().inventory().available()).isEqualTo(5);
    assertThat(result.state().inventory().reserved()).isZero();
    assertThat(result.state().inventory().sold()).isZero();
  }

  @Test
  void sessionsShareStockButCanOnlyPayOrCancelTheirOwnOrder() throws Exception {
    var first = new MockHttpSession();
    var second = new MockHttpSession();
    var original = open(first);
    var unrelated = open(second);
    assertThat(original.inventory().id()).isEqualTo(unrelated.inventory().id());
    mvc.perform(
            post("/api/demo/manual/actions")
                .session(first)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.BUY, 5))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inventory.available").value(0));
    mvc.perform(
            post("/api/demo/manual/actions")
                .session(second)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.BUY, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.inventory.available").value(0))
        .andExpect(jsonPath("$.result.code").value("INSUFFICIENT_STOCK"));
    assertThat(open(first).inventory().id()).isEqualTo(original.inventory().id());
    assertThat(open(first).inventory().available()).isZero();
    var secondView = open(second);
    assertThat(secondView.inventory().reserved()).isEqualTo(5);
    assertThat(secondView.order()).isNull();
    var firstOrder = open(first).order();
    assertThat(open(first).activity())
        .anyMatch(e -> e.code().equals("RESERVED") && e.actor().equals("You"));
    assertThat(secondView.activity())
        .anyMatch(e -> e.code().equals("RESERVED") && e.actor().startsWith("Visitor "));
    for (var action : new Action[] {Action.PAY, Action.CANCEL}) {
      mvc.perform(
              post("/api/demo/manual/actions")
                  .session(second)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"action\":\""
                          + action
                          + "\",\"quantity\":1,\"orderId\":\""
                          + firstOrder.id()
                          + "\"}"))
          .andExpect(status().isBadRequest());
    }
    assertThat(open(first).order().status()).isEqualTo(OrderStatus.RESERVED);
    assertThat(json.writeValueAsString(secondView)).doesNotContain(firstOrder.ownerId());
    mvc.perform(get("/api/demo/manual").session(first))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inventory.available").value(0));
    mvc.perform(get("/api/demo/manual").session(new MockHttpSession()))
        .andExpect(status().isNotFound());
  }

  @Test
  void writesRequireCsrfAndLocalHostAndCannotTargetAnArbitraryProduct() throws Exception {
    mvc.perform(post("/api/demo/manual")).andExpect(status().isForbidden());
    mvc.perform(post("http://remote.example/api/demo/manual").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(get("http://remote.example/api/demo/manual")).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/demo/manual/actions")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.ADD_STOCK, 1))))
        .andExpect(status().isNotFound());
    var session = new MockHttpSession();
    var initial = open(session);
    mvc.perform(
            post("/api/demo/manual/actions")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.ADD_STOCK, 1))))
        .andExpect(status().isForbidden());
    for (var command :
        new Command[] {new Command(Action.ADD_STOCK, -1), new Command(Action.BUY, 11)}) {
      mvc.perform(
              post("/api/demo/manual/actions")
                  .session(session)
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(command)))
          .andExpect(status().isBadRequest());
    }
    assertThat(open(session).inventory()).isEqualTo(initial.inventory());
  }

  @Test
  void newVisitorsAndARecreatedProductResolverKeepExistingInventory() {
    var workspace = manual.create();
    var changed = manual.act(workspace, new Command(Action.REMOVE_STOCK, 2)).state();
    var freshResolver = new SharedDemoProduct(catalog, products);
    assertThat(freshResolver.id()).isEqualTo(changed.inventory().id());
    assertThat(manual.state(manual.create()).inventory()).isEqualTo(changed.inventory());
    assertThat(products.findAll().stream().filter(p -> p.sku().equals(SharedDemoProduct.SKU)))
        .hasSize(1);
  }

  @Test
  void twoVisitorsContendForTheLastUnitInTheDatabase() throws Exception {
    var first = manual.create();
    var second = manual.create();
    manual.act(first, new Command(Action.REMOVE_STOCK, 4));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<ManualDemoService.Outcome> a =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Start timed out");
            return manual.act(first, new Command(Action.BUY, 1));
          };
      Callable<ManualDemoService.Outcome> b =
          () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS))
              throw new IllegalStateException("Start timed out");
            return manual.act(second, new Command(Action.BUY, 1));
          };
      var one = pool.submit(a);
      var two = pool.submit(b);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(
              java.util.List.of(
                  one.get(15, TimeUnit.SECONDS).statusCode(),
                  two.get(15, TimeUnit.SECONDS).statusCode()))
          .containsExactlyInAnyOrder(200, 409);
    } finally {
      start.countDown();
    }
    var state = manual.state(first);
    assertThat(state.inventory().available()).isZero();
    assertThat(state.inventory().reserved()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE product_id = ?",
                Long.class,
                state.inventory().id()))
        .isEqualTo(1L);
  }

  @Test
  void rolledBackAndScenarioOrdersNeverEnterSharedActivity() {
    var workspace = manual.create();
    var before = manual.state(workspace);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              orders.reserve(
                  "rollback-owner",
                  new dev.inventory.order.OrderDtos.ReserveRequest(before.inventory().id(), 1));
              status.setRollbackOnly();
            });
    var isolated =
        catalog.create(
            new CreateProduct(
                "TEST-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT),
                "Scenario fixture",
                1200,
                "CAD",
                1));
    orders.reserve(
        "scenario-owner", new dev.inventory.order.OrderDtos.ReserveRequest(isolated.id(), 1));
    assertThat(manual.state(workspace).activity()).isEqualTo(before.activity());
    assertThat(manual.state(workspace).inventory()).isEqualTo(before.inventory());
  }

  @Test
  void sharedLogRetainsOnlyTwoHundredEventsAndPollingDoesNotAppend() {
    var workspace = manual.create();
    for (int i = 0; i < 210; i++) {
      manual.act(workspace, new Command(i % 2 == 0 ? Action.ADD_STOCK : Action.REMOVE_STOCK, 1));
    }
    var state = manual.state(workspace);
    assertThat(state.activity()).hasSize(200);
    assertThat(state.activity().getLast().sequence() - state.activity().getFirst().sequence())
        .isEqualTo(199);
    assertThat(manual.state(workspace).activity()).isEqualTo(state.activity());
    assertThat(manual.state(manual.create()).activity()).hasSize(200);
  }

  @Test
  void rateLimitRejectsRapidWritesWithoutChangingInventoryOrFloodingTheLog() {
    var limited =
        new ManualDemoService(
            catalog,
            orders,
            java.time.Clock.systemUTC(),
            new SharedDemoProduct(catalog, products),
            activity,
            60000,
            transactions);
    var workspace = limited.create();
    var accepted = limited.act(workspace, new Command(Action.ADD_STOCK, 1));
    var rejected = limited.act(workspace, new Command(Action.REMOVE_STOCK, 1));
    assertThat(rejected.statusCode()).isEqualTo(429);
    assertThat(rejected.state().result().code()).isEqualTo("RATE_LIMITED");
    assertThat(rejected.state().retryAfterMs()).isBetween(1L, 60000L);
    assertThat(rejected.state().inventory()).isEqualTo(accepted.state().inventory());
    assertThat(rejected.state().activity()).isEqualTo(accepted.state().activity());
    assertThat(limited.act(limited.create(), new Command(Action.REMOVE_STOCK, 1)).statusCode())
        .isEqualTo(200);
  }

  private State open(MockHttpSession session) throws Exception {
    var response =
        mvc.perform(post("/api/demo/manual").session(session).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();
    return json.readValue(response.getResponse().getContentAsString(), State.class);
  }
}
