package io.cassyx.licensing.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.licensing.LicensingTestKeys;
import io.cassyx.licensing.config.LicensingProperties;
import io.cassyx.licensing.email.LicenseEmailSender;
import io.cassyx.licensing.mint.Ed25519LicenseMinter;
import io.cassyx.licensing.store.IssuedLicense;
import io.cassyx.licensing.store.IssuedLicenseRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * What happens when email fails AFTER payment (plan section 9.3). Someone has already been charged,
 * so the only acceptable behaviours are: keep the key, make the failure loud, and make it
 * recoverable.
 */
@SpringBootTest
@TestPropertySource(
    properties = {"spring.datasource.url=jdbc:h2:mem:cassyx-licensing-delivery;DB_CLOSE_DELAY=-1"})
class LicensingDeliveryTest {

  @DynamicPropertySource
  static void keys(DynamicPropertyRegistry registry) {
    LicensingTestKeys.register(registry);
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private IssuedLicenseRepository repository;
  @Autowired private LicenseVerifier verifier;
  @Autowired private LicensingProperties properties;

  private FlakySender sender;
  private LicensingService service;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM cassyx_issued_license");
    sender = new FlakySender();
    service =
        new LicensingService(
            new Ed25519LicenseMinter(LicensingTestKeys.privateKey()),
            verifier,
            repository,
            sender,
            properties);
  }

  @Test
  void keepsTheKeyRecoverableWhenTheEmailFails() {
    sender.working = false;

    IssuedLicense issued = service.issuePurchase("ops@example.com", "Example GmbH", "evt_1");

    // Minted and PERSISTED even though delivery failed: the purchase is not lost.
    assertThat(issued.deliveryState()).isEqualTo(IssuedLicense.FAILED);
    assertThat(verifier.verify(issued.licenseKey()).valid()).isTrue();
    assertThat(repository.findUndelivered()).hasSize(1);

    // ... and the retry endpoint's work actually delivers it once the provider recovers.
    sender.working = true;
    assertThat(service.retryUndelivered()).isEqualTo(1);
    assertThat(repository.findUndelivered()).isEmpty();
    assertThat(sender.sent).hasSize(1);
  }

  @Test
  void recoveryReSendsEveryKeyTheAddressEverBought() {
    service.issuePurchase("ops@example.com", "Example GmbH", "evt_1");
    service.issuePurchase("ops@example.com", "Example GmbH", "evt_2");
    sender.sent.clear();

    assertThat(service.recover("OPS@example.com")).isEqualTo(2);
    assertThat(sender.sent)
        .allSatisfy(reason -> assertThat(reason).isEqualTo(LicenseEmailSender.Reason.RECOVERY));
    // Two purchases from one address are two licences: the trial cap must not apply to buyers.
    assertThat(repository.findByEmail("ops@example.com")).hasSize(2);
  }

  @Test
  void recoveringAnUnknownAddressSendsNothingAndDoesNotThrow() {
    assertThat(service.recover("nobody@example.com")).isZero();
    assertThat(sender.sent).isEmpty();
  }

  /** Fails on demand, so the post-payment failure path is exercised rather than assumed. */
  private static final class FlakySender implements LicenseEmailSender {
    private final List<Reason> sent = new ArrayList<>();
    private boolean working = true;

    @Override
    public void send(IssuedLicense license, Reason reason) {
      if (!working) {
        throw new EmailDeliveryException("provider unavailable");
      }
      sent.add(reason);
    }
  }
}
