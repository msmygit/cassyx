package io.cassyx.licensing.email;

import io.cassyx.licensing.store.IssuedLicense;

/**
 * Delivers a minted licence to its buyer.
 *
 * <p>An interface with a logging default so the whole service runs end to end in development with
 * no provider account at all. Email is on the critical path: if it fails after payment, someone
 * paid and got nothing. Implementations must therefore THROW on failure rather than swallowing it -
 * the caller records the licence as undelivered, which keeps it visible and retryable and lets the
 * recovery endpoint fix it without anyone opening a ticket.
 */
public interface LicenseEmailSender {

  /**
   * @param reason why this email is being sent - purchase, trial or recovery. Appears in the body.
   * @throws EmailDeliveryException when delivery failed; never fail silently
   */
  void send(IssuedLicense license, Reason reason);

  enum Reason {
    PURCHASE,
    TRIAL,
    RECOVERY
  }

  /** Checked-at-the-call-site failure: unchecked, but never ignorable. */
  class EmailDeliveryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EmailDeliveryException(String message, Throwable cause) {
      super(message, cause);
    }

    public EmailDeliveryException(String message) {
      super(message);
    }
  }
}
