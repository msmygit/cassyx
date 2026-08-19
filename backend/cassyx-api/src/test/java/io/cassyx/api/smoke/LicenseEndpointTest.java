package io.cassyx.api.smoke;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cassyx.api.config.CassyxVersion;
import io.cassyx.api.license.LicenseController;
import io.cassyx.api.license.LicenseGate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/license} is ungated (plan section 9.1) and is the FIRST call the UI makes - it renders
 * nothing until this answers. A failure here is indistinguishable to the user from the whole product
 * being down, which is why these are smoke tests rather than unit tests.
 */
class LicenseEndpointTest {

  /**
   * The shipped default for {@code cassyx.license.public-key} is the literal {@code PLACEHOLDER}.
   * Building the Ed25519 verifier from it throws, and doing that eagerly in the constructor took the
   * entire application down at boot - a fresh {@code make up} served nothing and the UI showed
   * "Could not reach the cassyx API to check the license. (Not found)".
   *
   * <p>A placeholder credential must degrade to an honest answer, never to a dead application.
   */
  @Nested
  @WebMvcTest(LicenseController.class)
  // The controller delegates to the shared LicenseGate (plan section 9.1), which the request
  // filter also holds; a @WebMvcTest slice does not component-scan it, so import it explicitly.
  @Import({LicenseGate.class, CassyxVersion.class})
  @TestPropertySource(
      properties = {
        "cassyx.license.public-key=PLACEHOLDER",
        "cassyx.license.enforce=true",
        "cassyx.license.key="
      })
  class WithPlaceholderPublicKey {

    @Autowired private MockMvc mockMvc;

    @Test
    void startsAndAnswersRatherThanFailingToBoot() throws Exception {
      mockMvc
          .perform(get("/api/license"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.licensed").value(false))
          // The operator needs to know the KEY is unusable, not merely that a licence is absent.
          .andExpect(jsonPath("$.state").value("MALFORMED"))
          .andExpect(jsonPath("$.message").isNotEmpty());
    }
  }

  /** The default developer experience: enforcement off, everything unlocked, banner visible. */
  @Nested
  @WebMvcTest(LicenseController.class)
  // The controller delegates to the shared LicenseGate (plan section 9.1), which the request
  // filter also holds; a @WebMvcTest slice does not component-scan it, so import it explicitly.
  @Import({LicenseGate.class, CassyxVersion.class})
  @TestPropertySource(
      properties = {"cassyx.license.public-key=PLACEHOLDER", "cassyx.license.enforce=false"})
  class WithEnforcementDisabled {

    @Autowired private MockMvc mockMvc;

    @Test
    void reportsBypassDistinctlySoItIsNeverMistakenForAPaidInstance() throws Exception {
      mockMvc
          .perform(get("/api/license"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.licensed").value(true))
          .andExpect(jsonPath("$.bypass").value(true))
          // Plan section 9.2: the bypass edition is deliberately not a real edition name.
          .andExpect(jsonPath("$.edition").value("unlicensed-bypass"))
          .andExpect(jsonPath("$.state").value("BYPASS"));
    }
  }
}
