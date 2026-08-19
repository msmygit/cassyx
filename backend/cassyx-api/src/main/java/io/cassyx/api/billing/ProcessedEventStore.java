package io.cassyx.api.billing;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency on {@code event.id} (plan section 9.3), over the {@code cassyx_billing_event} ledger
 * from the V1 baseline.
 *
 * <p>Stripe retries a webhook until it gets a 2xx, and can deliver the same event more than once
 * regardless. Without this store one payment mints several licences and emails them all - a defect
 * customers never report because it looks generous.
 *
 * <p>The claim is a plain INSERT on the primary key, so concurrent deliveries of the same event
 * race in the database rather than in application code: exactly one wins.
 */
@Component
public class ProcessedEventStore {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessedEventStore.class);

  private static final int DETAIL_MAX = 1900;

  private final JdbcTemplate jdbc;

  public ProcessedEventStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @return true when this call owns the event, false when it was already processed
   */
  public boolean claim(String eventId, String eventType) {
    try {
      jdbc.update(
          "INSERT INTO cassyx_billing_event (event_id, event_type, received_at, status) "
              + "VALUES (?, ?, ?, ?)",
          eventId,
          eventType,
          Timestamp.from(Instant.now()),
          "RECEIVED");
      return true;
    } catch (DuplicateKeyException e) {
      LOG.info("Ignoring replayed Stripe event {} ({})", eventId, eventType);
      return false;
    }
  }

  /** Records the outcome so an operator can answer "did this payment ever mint a key?". */
  public void complete(String eventId, String status, String email, String detail) {
    jdbc.update(
        "UPDATE cassyx_billing_event SET status = ?, processed_at = ?, email = ?, detail = ? "
            + "WHERE event_id = ?",
        status,
        Timestamp.from(Instant.now()),
        email,
        truncate(detail),
        eventId);
  }

  /**
   * Releases the claim so Stripe's next retry is processed rather than swallowed as a duplicate.
   * Called only when fulfilment failed <em>after</em> the claim: keeping the row would turn a
   * transient outage into a permanently unfulfilled order that has already been charged.
   */
  public void release(String eventId) {
    jdbc.update("DELETE FROM cassyx_billing_event WHERE event_id = ?", eventId);
  }

  /** The recorded outcome, or empty when the event was never seen (or was released for retry). */
  public Optional<String> statusOf(String eventId) {
    return jdbc.query(
        "SELECT status FROM cassyx_billing_event WHERE event_id = ?",
        rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(),
        eventId);
  }

  private static String truncate(String detail) {
    if (detail == null) {
      return null;
    }
    return detail.length() > DETAIL_MAX ? detail.substring(0, DETAIL_MAX) : detail;
  }
}
