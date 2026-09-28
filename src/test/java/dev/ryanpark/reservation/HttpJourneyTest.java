package dev.ryanpark.reservation;

import com.fasterxml.jackson.databind.*;
import dev.ryanpark.reservation.events.OutboxRepository;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @ActiveProfiles("test")
class HttpJourneyTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired OutboxRepository outbox;
    HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
    record Result(int status, JsonNode body) {}
    String url(String path) { return "http://127.0.0.1:" + port + path; }
    Result request(String user, String method, String path, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(10));
        if (user != null) request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":test-" + user + "-password").getBytes(StandardCharsets.UTF_8)));
        if (!method.equals("GET")) {
            var token = request(null, "GET", "/api/csrf", null, null).body();
            request.header(token.get("headerName").asText(), token.get("token").asText());
        }
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), json.readTree(response.body()));
    }
    @Test void demoAndStylesAreServedWithoutAuthenticationWithCsp() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url("/"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Watch the stock", "app.js", "reserve-form");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        for (var path : List.of("/styles.css", "/app.js")) {
            var asset = client.send(HttpRequest.newBuilder(URI.create(url(path))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(asset.statusCode()).isEqualTo(200); assertThat(asset.body()).isNotBlank();
        }
    }
    @Test void completeJourneyOverRealHttpPreservesInventoryAndEvents() throws Exception {
        var product = request("admin", "POST", "/api/products", Map.of("sku", "HTTP-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT), "name", "HTTP widget", "priceCents", 750, "currency", "USD", "stock", 3), null);
        assertThat(product.status()).isEqualTo(201);
        var productId = product.body().get("id").asText(); var key = UUID.randomUUID().toString();
        var payload = Map.of("productId", productId, "quantity", 2);
        var order = request("alice", "POST", "/api/orders", payload, key);
        assertThat(order.status()).isEqualTo(201); var id = order.body().get("id").asText();
        assertThat(order.body().get("totalPriceCents").asLong()).isEqualTo(1500);
        assertThat(request("alice", "POST", "/api/orders", payload, key).body().get("id").asText()).isEqualTo(id);
        assertThat(request("alice", "POST", "/api/orders", Map.of("productId", productId, "quantity", 1), key).status()).isEqualTo(409);
        assertThat(request("bob", "GET", "/api/orders/" + id, null, null).status()).isEqualTo(404);
        assertThat(request("alice", "POST", "/api/admin/payments/" + id, Map.of("success", true), null).status()).isEqualTo(403);
        for (int retry = 0; retry < 2; retry++) {
            var paid = request("admin", "POST", "/api/admin/payments/" + id, Map.of("success", true), null);
            assertThat(paid.status()).isEqualTo(200); assertThat(paid.body().get("status").asText()).isEqualTo("CONFIRMED");
        }
        var stock = request("alice", "GET", "/api/products/" + productId, null, null).body();
        assertThat(stock.get("available").asInt()).isEqualTo(1);
        assertThat(stock.get("reserved").asInt()).isZero(); assertThat(stock.get("sold").asInt()).isEqualTo(2);
        assertThat(outbox.findByOrderIdOrderByRevisionAsc(UUID.fromString(id))).hasSize(2);
    }
}
