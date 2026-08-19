package io.cassyx.licensing.store;

import io.cassyx.license.api.License;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Persistence for minted licences and for webhook idempotency (plan sections 9.3, 9.4). */
@Repository
public class IssuedLicenseRepository {

  private static final String COLUMNS =
      "id, lic_code, email, holder_name, edition, seats, payload_ver, issued_on, expires_on, "
          + "scope_major, license_key, source_event, delivery_state, attempts";

  private static final RowMapper<IssuedLicense> MAPPER = IssuedLicenseRepository::map;

  private final JdbcTemplate jdbc;

  public IssuedLicenseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @throws DuplicateKeyException when this email already has a trial - the unique index is the
   *     rate limit, so two concurrent requests cannot both succeed
   */
  public void insert(IssuedLicense issued) {
    jdbc.update(
        "INSERT INTO cassyx_issued_license (" + COLUMNS + ", created_at, trial_email) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        issued.id(),
        issued.licCode(),
        issued.email(),
        issued.holderName(),
        issued.edition(),
        issued.seats(),
        issued.payloadVersion(),
        Date.valueOf(issued.issuedOn()),
        issued.expiresOn() == null ? null : Date.valueOf(issued.expiresOn()),
        issued.scope(),
        issued.licenseKey(),
        issued.sourceEvent(),
        issued.deliveryState(),
        issued.attempts(),
        Timestamp.from(Instant.now()),
        // Only trials participate in the one-per-email unique index.
        License.TRIAL_EDITION.equals(issued.edition()) ? normalise(issued.email()) : null);
  }

  /** Newest first, so recovery emails the most recent key rather than an ancient one. */
  public List<IssuedLicense> findByEmail(String email) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM cassyx_issued_license WHERE email = ? "
            + "ORDER BY created_at DESC",
        MAPPER,
        normalise(email));
  }

  public Optional<IssuedLicense> findTrialByEmail(String email) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM cassyx_issued_license WHERE trial_email = ?",
            MAPPER,
            normalise(email))
        .stream()
        .findFirst();
  }

  /** Everything minted but not yet delivered - the retry queue. */
  public List<IssuedLicense> findUndelivered() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM cassyx_issued_license WHERE delivery_state <> ? "
            + "ORDER BY created_at",
        MAPPER,
        IssuedLicense.SENT);
  }

  public void recordDelivery(String id, String state, String error, int attempts) {
    jdbc.update(
        "UPDATE cassyx_issued_license SET delivery_state = ?, delivery_error = ?, attempts = ?, "
            + "last_attempt = ? WHERE id = ?",
        state,
        error == null || error.length() <= 950 ? error : error.substring(0, 950),
        attempts,
        Timestamp.from(Instant.now()),
        id);
  }

  /** @return true when this call owns the event; false when it was already processed */
  public boolean claimEvent(String eventId, String eventType) {
    try {
      jdbc.update(
          "INSERT INTO cassyx_licensing_event (event_id, event_type, received_at, status) "
              + "VALUES (?,?,?,?)",
          eventId,
          eventType,
          Timestamp.from(Instant.now()),
          "RECEIVED");
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public void completeEvent(String eventId, String status, String email, String detail) {
    jdbc.update(
        "UPDATE cassyx_licensing_event SET status = ?, email = ?, detail = ? WHERE event_id = ?",
        status,
        email,
        detail,
        eventId);
  }

  /** Releases a claim so Stripe's retry is processed rather than swallowed as a duplicate. */
  public void releaseEvent(String eventId) {
    jdbc.update("DELETE FROM cassyx_licensing_event WHERE event_id = ?", eventId);
  }

  public Optional<String> eventStatus(String eventId) {
    return jdbc.query(
        "SELECT status FROM cassyx_licensing_event WHERE event_id = ?",
        rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty(),
        eventId);
  }

  /** Addresses are matched case-insensitively; nobody expects Ops@ and ops@ to be two customers. */
  public static String normalise(String email) {
    return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private static IssuedLicense map(ResultSet rs, int rowNum) throws SQLException {
    Date expires = rs.getDate("expires_on");
    // wasNull() reports on the LAST column read, so the null check has to happen here rather than
    // inline in the constructor call - inline it would report on whatever was read most recently.
    int rawScope = rs.getInt("scope_major");
    Integer scope = rs.wasNull() ? null : rawScope;
    return new IssuedLicense(
        rs.getString("id"),
        rs.getString("lic_code"),
        rs.getString("email"),
        rs.getString("holder_name"),
        rs.getString("edition"),
        rs.getInt("seats"),
        rs.getInt("payload_ver"),
        rs.getDate("issued_on").toLocalDate(),
        expires == null ? null : expires.toLocalDate(),
        scope,
        rs.getString("license_key"),
        rs.getString("source_event"),
        rs.getString("delivery_state"),
        rs.getInt("attempts"));
  }
}
