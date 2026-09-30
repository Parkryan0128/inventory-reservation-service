package dev.ryanpark.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.ryanpark.reservation.demo.DemoController;
import dev.ryanpark.reservation.demo.DemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DemoDisabledTest {
  @Autowired ApplicationContext context;
  @Autowired MockMvc mvc;

  @Test
  void demoDoesNotExistOutsideDemoProfileEvenForAdmin() throws Exception {
    assertThat(context.getBeansOfType(DemoController.class)).isEmpty();
    assertThat(context.getBeansOfType(DemoService.class)).isEmpty();
    mvc.perform(get("/api/demo/status")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/demo/run/race").with(user("admin").roles("ADMIN", "CUSTOMER")).with(csrf()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/demo.html").with(user("admin").roles("ADMIN", "CUSTOMER")))
        .andExpect(status().isForbidden());
  }
}
