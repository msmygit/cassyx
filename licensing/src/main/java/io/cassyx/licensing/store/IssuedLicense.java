package io.cassyx.licensing.store;

import java.time.LocalDate;

/**
 * A minted licence as persisted. {@code licenseKey} is the signed key exactly as the customer
 * receives it - it is not a secret in the usual sense (the customer pastes it into their own UI),
 * but it IS the thing they paid for, so losing this row means the purchase is unrecoverable
 * without re-minting.
 */
public record IssuedLicense(
    String id,
    String licCode,
    String email,
    String holderName,
    String edition,
    int seats,
    int payloadVersion,
    LocalDate issuedOn,
    LocalDate expiresOn,
    Integer scope,
    String licenseKey,
    String sourceEvent,
    String deliveryState,
    int attempts) {

  public static final String PENDING = "PENDING";
  public static final String SENT = "SENT";
  public static final String FAILED = "FAILED";
}
