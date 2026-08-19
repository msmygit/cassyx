package io.cassyx.licensing.service;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.licensing.config.LicensingProperties;
import io.cassyx.licensing.email.LicenseEmailSender;
import io.cassyx.licensing.mint.Ed25519LicenseMinter;
import io.cassyx.licensing.store.IssuedLicense;
import io.cassyx.licensing.store.IssuedLicenseRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Mint, persist, email (plan section 9.3), plus trial issuance (9.4) and self-serve recovery.
 *
 * <p>The order is deliberate and load-bearing: mint, then PERSIST, then email. Emailing before
 * persisting means a delivery that cannot be recovered if the write then fails; persisting first
 * means every minted key is recoverable no matter what the mail provider does.
 */
@Service
public class LicensingService {

  private static final Logger LOG = LoggerFactory.getLogger(LicensingService.class);

  private final Ed25519LicenseMinter minter;
  private final LicenseVerifier verifier;
  private final IssuedLicenseRepository repository;
  private final LicenseEmailSender email;
  private final LicensingProperties properties;

  public LicensingService(
      Ed25519LicenseMinter minter,
      LicenseVerifier verifier,
      IssuedLicenseRepository repository,
      LicenseEmailSender email,
      LicensingProperties properties) {
    this.minter = minter;
    this.verifier = verifier;
    this.repository = repository;
    this.email = email;
    this.properties = properties;
  }

  /** A paid purchase: perpetual key, scoped to the major version sold (plan section 9.5). */
  public IssuedLicense issuePurchase(String buyerEmail, String buyerName, String sourceEvent) {
    License license =
        new License(
            Ed25519LicenseMinter.newLicenseCode(),
            IssuedLicenseRepository.normalise(buyerEmail),
            buyerName,
            minter.today(),
            License.STANDARD_EDITION,
            Math.max(1, properties.seats()),
            properties.payloadVersion(),
            null,
            properties.scope() > 0 ? properties.scope() : null);
    return issue(license, sourceEvent, LicenseEmailSender.Reason.PURCHASE);
  }

  /**
   * A trial (plan section 9.4). Time-limited via {@code expires}, which is INCLUSIVE: a 14-day
   * trial issued on the 17th expires on the 30th and still works all day on the 30th.
   *
   * @return empty when this address already had a trial - 409 rather than silently re-arming the
   *     clock, which would make the trial an infinite renewal loop
   */
  public Optional<IssuedLicense> issueTrial(String buyerEmail, String buyerName) {
    String normalised = IssuedLicenseRepository.normalise(buyerEmail);
    if (repository.findTrialByEmail(normalised).isPresent()) {
      return Optional.empty();
    }
    LocalDate issued = minter.today();
    int days = properties.trialDays() > 0 ? properties.trialDays() : License.DEFAULT_TRIAL_DAYS;
    License license =
        new License(
            Ed25519LicenseMinter.newLicenseCode(),
            normalised,
            buyerName,
            issued,
            License.TRIAL_EDITION,
            1,
            properties.payloadVersion(),
            // Inclusive expiry: issued + (days - 1) means a 14-day trial covers 14 whole days.
            issued.plusDays(days - 1L),
            properties.scope() > 0 ? properties.scope() : null);
    try {
      return Optional.of(issue(license, null, LicenseEmailSender.Reason.TRIAL));
    } catch (DuplicateKeyException e) {
      // Lost the race against a concurrent request for the same address; the other one won.
      return Optional.empty();
    }
  }

  /**
   * Re-sends every key already issued to an address. Deliberately says nothing about whether the
   * address is known: the caller always gets the same answer, so this cannot be used to enumerate
   * customers.
   *
   * @return how many keys were re-sent
   */
  public int recover(String buyerEmail) {
    List<IssuedLicense> issued = repository.findByEmail(buyerEmail);
    int sent = 0;
    for (IssuedLicense license : issued) {
      if (deliver(license, LicenseEmailSender.Reason.RECOVERY)) {
        sent++;
      }
    }
    if (issued.isEmpty()) {
      LOG.info("Recovery requested for an address with no licences");
    }
    return sent;
  }

  /** Re-attempts every undelivered key. Safe to call repeatedly; that is the point. */
  public int retryUndelivered() {
    int sent = 0;
    for (IssuedLicense license : repository.findUndelivered()) {
      if (deliver(license, LicenseEmailSender.Reason.RECOVERY)) {
        sent++;
      }
    }
    return sent;
  }

  public List<IssuedLicense> findByEmail(String buyerEmail) {
    return repository.findByEmail(buyerEmail);
  }

  private IssuedLicense issue(
      License license, String sourceEvent, LicenseEmailSender.Reason reason) {
    String key = minter.mint(license);
    // Self-check before anyone is told the purchase succeeded. A key that does not verify against
    // the shipped verifier is worse than no key: the customer has paid and holds something the
    // product rejects, and nothing in the flow would otherwise notice.
    LicenseStatus status = verifier.verify(key);
    if (!status.valid()) {
      throw new IllegalStateException(
          "Minted a licence that does not verify against the public key: " + status.reason());
    }
    IssuedLicense issued =
        new IssuedLicense(
            UUID.randomUUID().toString(),
            license.lic(),
            license.email(),
            license.name(),
            license.edition(),
            license.seats(),
            license.ver(),
            license.issued(),
            license.expires(),
            license.scope(),
            key,
            sourceEvent,
            IssuedLicense.PENDING,
            0);
    repository.insert(issued);
    deliver(issued, reason);
    // Returned with the state the caller can act on; delivery may still be PENDING/FAILED, and the
    // recovery endpoint exists precisely for that case.
    return repository.findByEmail(issued.email()).stream()
        .filter(row -> row.id().equals(issued.id()))
        .findFirst()
        .orElse(issued);
  }

  private boolean deliver(IssuedLicense license, LicenseEmailSender.Reason reason) {
    int attempts = license.attempts() + 1;
    try {
      email.send(license, reason);
      repository.recordDelivery(license.id(), IssuedLicense.SENT, null, attempts);
      return true;
    } catch (RuntimeException e) {
      // Loud, and left recoverable. The money has already changed hands at this point.
      LOG.error(
          "LICENCE {} FOR {} WAS MINTED BUT NOT DELIVERED (attempt {}). "
              + "It is retryable via POST /licensing/recover.",
          license.licCode(),
          license.email(),
          attempts,
          e);
      repository.recordDelivery(license.id(), IssuedLicense.FAILED, e.getMessage(), attempts);
      return false;
    }
  }
}
