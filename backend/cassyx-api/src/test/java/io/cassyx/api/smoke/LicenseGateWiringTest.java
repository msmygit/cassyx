package io.cassyx.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cassyx.api.license.LicenseGate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gate of plan section 9.1 as the running application actually serves it.
 *
 * <p>{@code LicenseGateFilterTest} drives the filter object directly, which says nothing about
 * whether it is REGISTERED. That distinction is the whole failure mode this workstream exists to
 * fix: before this branch every piece of the monetization stack was built and tested, and none of
 * it was in front of a request.
 */
class LicenseGateWiringTest {

  /** The shipped default: enforcement on, no key, placeholder public key. */
  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @TestPropertySource(
      properties = {
        "spring.datasource.url=jdbc:h2:mem:cassyx-gate-locked;DB_CLOSE_DELAY=-1",
        "cassyx.license.enforce=true",
        "cassyx.license.key="
      })
  class Locked {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    @Test
    void exactlyOneLicenceGateIsPublished() {
      // Two gates would be two verdicts, and nothing would report the disagreement.
      assertThat(context.getBeanNamesForType(LicenseGate.class)).hasSize(1);
    }

    @Test
    void aGatedApiPathIsRefusedWith402ProblemJson() throws Exception {
      mockMvc
          .perform(get("/api/connections"))
          .andExpect(status().isPaymentRequired())
          .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
          .andExpect(jsonPath("$.state").value("MALFORMED"))
          .andExpect(jsonPath("$.title").value("License required"));
    }

    @Test
    void healthStaysReachableSoTheContainerCanStillBeProbed() throws Exception {
      mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void licenceStaysReachableSoAConfigGapIsDiagnosable() throws Exception {
      // An unusable public key must be reportable, not silent - this is the endpoint that says so.
      mockMvc
          .perform(get("/api/license"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.licensed").value(false))
          .andExpect(jsonPath("$.state").value("MALFORMED"));
    }
  }

  /** The developer and Compose default (plan section 9.2), and what the E2E stack runs as. */
  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  @TestPropertySource(
      properties = {
        "spring.datasource.url=jdbc:h2:mem:cassyx-gate-bypassed;DB_CLOSE_DELAY=-1",
        "cassyx.license.enforce=false"
      })
  class Bypassed {

    @Autowired private MockMvc mockMvc;

    @Test
    void gatedPathsAreServed() throws Exception {
      // Not asserting 200 - the endpoint has its own opinions about an absent connection. Asserting
      // that the LICENCE gate is not what stopped it, which is the only thing this test owns.
      assertThat(mockMvc.perform(get("/api/connections")).andReturn().getResponse().getStatus())
          .isNotEqualTo(402);
    }
  }
}
