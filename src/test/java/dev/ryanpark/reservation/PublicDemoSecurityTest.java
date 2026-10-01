package dev.ryanpark.reservation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"DEMO_HOST=inventory.example.com", "app.demo.scenario-interval=PT0S"})
@ActiveProfiles({"public-demo", "test"})
@AutoConfigureMockMvc
class PublicDemoSecurityTest {
  private static final String SITE = "https://inventory.example.com";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void publicHostSupportsRealCsrfSessionAndManualMode() throws Exception {
    var session = new MockHttpSession();
    var response =
        mvc.perform(get(SITE + "/api/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn();
    var token = json.readTree(response.getResponse().getContentAsString());
    mvc.perform(
            post(SITE + "/api/demo/manual")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isOk());
    mvc.perform(get(SITE + "/api/demo/manual").session(session)).andExpect(status().isOk());
    mvc.perform(
            post(SITE + "/api/demo/run/race")
                .session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(true));
  }

  @Test
  void csrfAndHostChecksStillProtectPublicWrites() throws Exception {
    mvc.perform(post(SITE + "/api/demo/manual")).andExpect(status().isForbidden());
    mvc.perform(post(SITE + "/api/demo/run/race").with(csrf().useInvalidToken()))
        .andExpect(status().isForbidden());
    mvc.perform(get("https://other.example.com/api/demo/status"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEMO_HOST_NOT_ALLOWED"));
    mvc.perform(post("https://other.example.com/api/demo/manual").with(csrf()))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("https://other.example.com/api/demo/manual/actions")
                .with(csrf())
                .contentType("application/json")
                .content("{\"action\":\"BUY\",\"quantity\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void normalApisStayClosedEvenToAnAuthenticatedAdmin() throws Exception {
    for (var path :
        new String[] {
          "/api/admin/status", "/api/orders", "/api/products", "/actuator/prometheus", "/index.html"
        }) {
      mvc.perform(get(SITE + path).with(user("admin").roles("ADMIN", "CUSTOMER")))
          .andExpect(status().isForbidden());
    }
    mvc.perform(post(SITE + "/api/products").with(user("admin").roles("ADMIN")).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  void homeAssetsAndHealthAreAvailable() throws Exception {
    mvc.perform(get(SITE + "/")).andExpect(status().isOk()).andExpect(forwardedUrl("/demo.html"));
    for (var path :
        new String[] {
          "/demo.html", "/demo.js", "/demo-client.js", "/demo.css", "/actuator/health"
        }) {
      mvc.perform(get(SITE + path)).andExpect(status().isOk());
    }
  }
}
