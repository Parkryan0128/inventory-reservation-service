package dev.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.inventory.events.OrderEventConsumer;
import dev.inventory.events.OutboxRelay;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class AdminOperationsTest extends InventoryTestSupport {
  @Autowired ObjectMapper json;
  @Autowired OutboxRelay relay;
  @Autowired OrderEventConsumer consumer;

  @Test
  void newestFiftyOrdersSpanOwnersAndKeepTheirCreationOrderAfterPayment() throws Exception {
    assertThat(adminOrders()).isEmpty();
    var product = TestProducts.create(catalog, 51);
    var request = new ReserveRequest(product.id(), 1);
    var newestFirst = new ArrayList<OrderView>();
    for (int i = 0; i < 51; i++) {
      newestFirst.addFirst(service.reserve(i % 2 == 0 ? "alice" : "bob", request));
      clock.advance(Duration.ofSeconds(1));
    }
    service.payment(newestFirst.getLast().id(), true);
    newestFirst.set(1, service.cancel("bob", newestFirst.get(1).id()));
    assertThat(adminOrders()).containsExactlyElementsOf(newestFirst.subList(0, 50));
    assertStatus(51, 53, 0);
  }

  @Test
  void statusDistinguishesUnpublishedEventsFromConsumerReceipts() throws Exception {
    assertStatus(0, 0, 0);
    var product = TestProducts.create(catalog, 2);
    var request = new ReserveRequest(product.id(), 2);
    var order = placement.place("alice", "checkout", request);
    placement.place("alice", "checkout", request);
    assertStatus(1, 1, 0);
    service.payment(order.id(), true);
    service.payment(order.id(), true);
    assertStatus(1, 2, 0);

    var events = outbox.findByOrderIdOrderByRevisionAsc(order.id());
    var first = events.getFirst();
    var second = events.getLast();
    doThrow(new IllegalStateException("broker unavailable"))
        .doNothing()
        .when(publisher)
        .publish(order.id(), first.payload());
    assertThat(relay.publish(first.id())).isFalse();
    assertThat(relay.publish(second.id())).isTrue();
    assertStatus(1, 1, 0);
    consumer.accept(second.payload());
    consumer.accept(second.payload());
    assertStatus(1, 1, 1);

    clock.set(outbox.findById(first.id()).orElseThrow().nextAttemptAt());
    assertThat(relay.publish(first.id())).isTrue();
    assertStatus(1, 0, 1);
    consumer.accept(first.payload());
    assertStatus(1, 0, 2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/admin/orders", "/api/admin/status"})
  void adminReportsRequireAnAdministrator(String route) throws Exception {
    mvc.perform(get(route))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get(route).with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  private List<OrderView> adminOrders() throws Exception {
    var response =
        mvc.perform(get("/api/admin/orders").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readerForListOf(OrderView.class).readValue(response.getContentAsByteArray());
  }

  private void assertStatus(int orders, int pending, int receipts) throws Exception {
    mvc.perform(get("/api/admin/status").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").value(orders))
        .andExpect(jsonPath("$.pendingEvents").value(pending))
        .andExpect(jsonPath("$.auditReceipts").value(receipts));
  }
}
