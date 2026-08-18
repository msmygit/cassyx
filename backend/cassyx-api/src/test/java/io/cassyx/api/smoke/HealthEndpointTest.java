package io.cassyx.api.smoke;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cassyx.api.health.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /api/health} is one of the ungated paths of plan section 9.1. */
@WebMvcTest(HealthController.class)
class HealthEndpointTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void healthIsUp() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /**
   * Guards the contract. {@code ServiceHealth} in {@code openapi/cassyx-api.yaml} lists both {@code
   * status} and {@code version} as required, and the first implementation shipped only {@code
   * status} - drift that typechecks, serves 200, and breaks the generated client. Plan section 2.3
   * makes the contract binding, so assert the required fields are actually present.
   */
  @Test
  void healthMatchesTheServiceHealthContract() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").isString())
        .andExpect(jsonPath("$.version").isString())
        .andExpect(jsonPath("$.version").isNotEmpty());
  }
}
