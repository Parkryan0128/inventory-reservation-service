package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.inventory.inventory.ProductDtos.ProductView;
import dev.inventory.order.OrderDtos.OrderView;
import dev.inventory.order.OrderDtos.ReserveRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InventoryQueryTest extends InventoryTestSupport {
  @Test
  void ordersPageNewestFirstAndNeverIncludeAnotherOwner() throws Exception {
    var product = TestProducts.create(catalog, 60);
    var expected = new ArrayList<OrderView>();
    for (int i = 0; i < 53; i++) {
      expected.add(service.reserve("alice", new ReserveRequest(product.id(), 1)));
      clock.advance(Duration.ofSeconds(1));
    }
    service.reserve("bob", new ReserveRequest(product.id(), 1));
    expected.sort(Comparator.comparing(OrderView::createdAt).reversed());

    assertThat(service.list("alice", 0)).containsExactlyElementsOf(expected.subList(0, 50));
    assertThat(service.list("alice", 1)).containsExactlyElementsOf(expected.subList(50, 53));
    assertThat(service.list("alice", 2)).isEmpty();
    assertThat(service.list("nobody", 0)).isEmpty();
    mvc.perform(get("/api/orders?page=1").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$[0].id").value(expected.get(50).id().toString()));
    mvc.perform(get("/api/orders?page=0").with(user("bob").roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].ownerId").value("bob"));
  }

  @Test
  void catalogPagesAreSortedBySkuAndEndWithAnEmptyPage() throws Exception {
    assertThat(catalog.list(0)).isEmpty();
    var expected = new ArrayList<ProductView>();
    for (int i = 0; i < 51; i++) expected.add(TestProducts.create(catalog, 0));
    expected.sort(Comparator.comparing(ProductView::sku));
    assertThat(catalog.list(0)).containsExactlyElementsOf(expected.subList(0, 50));
    assertThat(catalog.list(1)).containsExactly(expected.getLast());
    assertThat(catalog.list(2)).isEmpty();
    mvc.perform(get("/api/products?page=1").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].sku").value(expected.getLast().sku()));
    mvc.perform(get("/api/products?page=2").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "abc", "2147483648"})
  void invalidPagesReturnClientErrors(String page) throws Exception {
    for (var path : new String[] {"/api/orders", "/api/products"}) {
      mvc.perform(get(path).param("page", page).with(user("alice").roles("CUSTOMER")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
  }
}
