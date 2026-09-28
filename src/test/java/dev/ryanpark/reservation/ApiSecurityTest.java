package dev.ryanpark.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ryanpark.reservation.inventory.CatalogService;
import dev.ryanpark.reservation.inventory.ProductDtos.CreateProduct;
import dev.ryanpark.reservation.order.OrderService;
import dev.ryanpark.reservation.order.OrderDtos.ReserveRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class ApiSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CatalogService catalog;
    @Autowired OrderService orders;
    UUID product() { return catalog.create(new CreateProduct("API-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "API product", 999, "USD", 5)).id(); }
    @Test void anonymousCannotReadOrders() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
    @Test void actualPasswordIsCheckedAndIdentityIsNotPersistedInSession() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/api/me").session(session).with(httpBasic("alice", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").session(session).with(httpBasic("alice", "test-alice-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("alice"));
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }
    @Test void csrfProtectsAuthenticatedWritesAndRealTokenWorks() throws Exception {
        var session = new MockHttpSession(); var payload = json.writeValueAsString(new ReserveRequest(product(), 1));
        mvc.perform(post("/api/orders").session(session).with(httpBasic("alice", "test-alice-password"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        var response = mvc.perform(get("/api/csrf").session(session)).andExpect(status().isOk()).andReturn();
        var token = json.readTree(response.getResponse().getContentAsString());
        mvc.perform(post("/api/orders").session(session).with(httpBasic("alice", "test-alice-password"))
                .header(token.get("headerName").asText(), token.get("token").asText())
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());
    }
    @Test void userHeaderCannotImpersonateAnotherOwner() throws Exception {
        mvc.perform(post("/api/orders").with(user("alice").roles("CUSTOMER")).with(csrf())
                .header("X-Customer-Id", "bob").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ReserveRequest(product(), 1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.ownerId").value("alice"));
    }
    @Test void otherOwnerCannotReadOrCancel() throws Exception {
        var order = orders.reserve("alice", new ReserveRequest(product(), 1));
        mvc.perform(get("/api/orders/" + order.id()).with(user("bob").roles("CUSTOMER"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + order.id() + "/cancel").with(user("bob").roles("CUSTOMER")).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void paymentSimulationRequiresAdmin() throws Exception {
        var order = orders.reserve("alice", new ReserveRequest(product(), 1));
        mvc.perform(post("/api/admin/payments/" + order.id()).with(user("alice").roles("CUSTOMER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/payments/" + order.id()).with(user("admin").roles("ADMIN", "CUSTOMER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"success\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }
    @Test void malformedAndMissingKeyAreClientErrors() throws Exception {
        mvc.perform(post("/api/orders").with(user("alice").roles("CUSTOMER")).with(csrf())
                .header("Idempotency-Key", "abc").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/orders").with(user("alice").roles("CUSTOMER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ReserveRequest(product(), 1))))
                .andExpect(status().isBadRequest());
    }
    @Test void catalogCreationRequiresAdminAndDuplicateSkuIsConflict() throws Exception {
        var payload = json.writeValueAsString(new CreateProduct("DUP-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "Widget", 10, "USD", 1));
        mvc.perform(post("/api/products").with(user("alice").roles("CUSTOMER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mvc.perform(post("/api/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated());
        mvc.perform(post("/api/products").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
    }
}
