package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ryanpark.reservation.demo.ManualDemoService;
import dev.ryanpark.reservation.demo.ManualDemoService.Action;
import dev.ryanpark.reservation.demo.ManualDemoService.Command;
import dev.ryanpark.reservation.demo.ManualDemoService.State;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderStatus;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles({"demo", "test"})
@AutoConfigureMockMvc
class ManualDemoTest {
  @Autowired ManualDemoService manual;
  @Autowired CatalogService catalog;
  @Autowired OrderService orders;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void removingAndAddingStockChangesRealPurchaseOutcomesOnTheSameProduct() {
    var workspace = manual.create();
    var initial = manual.state(workspace);
    var removed = manual.act(workspace, new Command(Action.REMOVE_STOCK, 5));
    assertThat(removed.statusCode()).isEqualTo(200);
    assertThat(catalog.get(initial.inventory().id()).available()).isZero();
    var rejected = manual.act(workspace, new Command(Action.BUY, 1));
    assertThat(rejected.statusCode()).isEqualTo(409);
    assertThat(rejected.state().activity().getLast().code()).isEqualTo("INSUFFICIENT_STOCK");
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
    assertThat(refreshed.activity().getLast().code()).isEqualTo("SNAPSHOT");
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
  void sessionsKeepTheirOwnProductAndOpeningAgainDoesNotResetIt() throws Exception {
    var first = new MockHttpSession();
    var second = new MockHttpSession();
    var original = open(first);
    var unrelated = open(second);
    assertThat(original.inventory().id()).isNotEqualTo(unrelated.inventory().id());
    mvc.perform(
            post("/api/demo/manual/actions")
                .session(first)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.REMOVE_STOCK, 5))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inventory.available").value(0));
    mvc.perform(
            post("/api/demo/manual/actions")
                .session(first)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Command(Action.BUY, 1))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.inventory.available").value(0))
        .andExpect(jsonPath("$.activity[-1].code").value("INSUFFICIENT_STOCK"));
    assertThat(open(first).inventory().id()).isEqualTo(original.inventory().id());
    assertThat(open(first).inventory().available()).isZero();
    assertThat(open(second).inventory()).isEqualTo(unrelated.inventory());
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
        new Command[] {new Command(Action.ADD_STOCK, -1), new Command(Action.BUY, 10001)}) {
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

  private State open(MockHttpSession session) throws Exception {
    var response =
        mvc.perform(post("/api/demo/manual").session(session).with(csrf()))
            .andExpect(status().isOk())
            .andReturn();
    return json.readValue(response.getResponse().getContentAsString(), State.class);
  }
}
