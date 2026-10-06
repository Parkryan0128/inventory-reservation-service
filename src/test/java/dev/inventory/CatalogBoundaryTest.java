package dev.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class CatalogBoundaryTest extends InventoryTestSupport {
  @Autowired ObjectMapper json;

  @ParameterizedTest
  @CsvSource({
    "priceCents, 0, false", "priceCents, 1, true", "priceCents, 1000000000, true",
    "priceCents, 1000000001, false", "stock, -1, false", "stock, 0, true",
    "stock, 1000000, true", "stock, 1000001, false"
  })
  void productNumericBoundariesAreEnforcedByTheApi(String field, long value, boolean valid)
      throws Exception {
    var body =
        json.createObjectNode()
            .put("sku", "BOUNDARY")
            .put("name", "Product")
            .put("priceCents", 1)
            .put("currency", "CAD")
            .put("stock", 0);
    body.put(field, value);
    mvc.perform(
            post("/api/products")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(valid ? 201 : 400));
    assertThat(catalog.list(0)).hasSize(valid ? 1 : 0);
    assertThat(outbox.count()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "sku, 1, true",
    "sku, 64, true",
    "sku, 65, false",
    "name, 1, true",
    "name, 160, true",
    "name, 161, false"
  })
  void productTextLengthsAcceptTheLimitAndRejectTheNextCharacter(
      String field, int length, boolean valid) throws Exception {
    var body =
        json.createObjectNode()
            .put("sku", "BOUNDARY")
            .put("name", "Product")
            .put("priceCents", 1)
            .put("currency", "USD")
            .put("stock", 0);
    body.put(field, "A".repeat(length));
    mvc.perform(
            post("/api/products")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().is(valid ? 201 : 400));
    assertThat(catalog.list(0)).hasSize(valid ? 1 : 0);
  }

  @Test
  void stockAdjustmentsReachTheLimitAndCannotRemoveReservedOrSoldUnits() {
    var product = TestProducts.create(catalog, 990_000);
    assertThat(catalog.adjustStock(product.id(), 10_000).initialStock()).isEqualTo(1_000_000);
    assertThatThrownBy(() -> catalog.adjustStock(product.id(), 1))
        .isInstanceOfSatisfying(
            dev.inventory.common.ApiException.class,
            error -> assertThat(error.code()).isEqualTo("STOCK_LIMIT"));

    var id = TestProducts.create(catalog, 2).id();
    var order = service.reserve("alice", new dev.inventory.order.OrderDtos.ReserveRequest(id, 1));
    catalog.adjustStock(id, -1);
    assertThatThrownBy(() -> catalog.adjustStock(id, -1))
        .isInstanceOf(dev.inventory.common.ApiException.class);
    service.payment(order.id(), true);
    assertThatThrownBy(() -> catalog.adjustStock(id, -1))
        .isInstanceOf(dev.inventory.common.ApiException.class);
    var remaining = catalog.get(id);
    assertThat(remaining.available()).isZero();
    assertThat(remaining.reserved()).isZero();
    assertThat(remaining.sold()).isEqualTo(1);
    assertThat(remaining.initialStock()).isEqualTo(1);
  }
}
