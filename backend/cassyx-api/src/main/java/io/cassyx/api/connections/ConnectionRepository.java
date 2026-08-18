package io.cassyx.api.connections;

import io.cassyx.core.api.EncryptedValue;
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

/**
 * {@code cassyx_connection} access over plain {@link JdbcTemplate}.
 *
 * <p>No ORM: the table is a handful of rows read once per page load and written by hand, and the
 * one thing that genuinely matters here - that every credential column round-trips as ciphertext
 * plus its own nonce - is clearer as explicit column lists than as annotations.
 */
@Repository
public class ConnectionRepository {

  private static final String COLUMNS =
      """
      id, name, mode, description, contact_points, local_datacenter, protocol_version_name,
      username, password_cipher, password_iv, default_keyspace, request_timeout_millis, tags,
      advanced_config,
      astra_database_id, astra_database_name, astra_token_cipher, astra_token_iv,
      scb_acquisition_mode, scb_region, scb_type, scb_custom_domain, scb_path,
      scb_bundle_cipher, scb_bundle_iv, scb_bundle_fetched_at, scb_file_name, scb_size_bytes,
      scb_sha256, scb_source, scb_cache_key, scb_validated,
      ssl_enabled, ssl_hostname_validation, ssl_cipher_suites,
      truststore_cipher, truststore_iv, truststore_password_cipher, truststore_password_iv,
      truststore_file_name, truststore_type,
      keystore_cipher, keystore_iv, keystore_password_cipher, keystore_password_iv,
      keystore_file_name, keystore_type,
      ssh_enabled, ssh_host, ssh_port, ssh_username,
      ssh_password_cipher, ssh_password_iv,
      ssh_private_key_cipher, ssh_private_key_iv,
      ssh_private_key_passphrase_cipher, ssh_private_key_passphrase_iv,
      ssh_local_port, ssh_remote_host, ssh_remote_port, ssh_strict_host_key_checking,
      ssh_known_hosts_entry,
      created_at, updated_at, last_connected_at
      """;

  private final JdbcTemplate jdbc;

  public ConnectionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<ConnectionRow> findAll() {
    return jdbc.query("SELECT " + COLUMNS + " FROM cassyx_connection ORDER BY name", MAPPER);
  }

  public Optional<ConnectionRow> findById(String id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM cassyx_connection WHERE id = ?", MAPPER, id)
        .stream()
        .findFirst();
  }

  public boolean existsByName(String name, String excludingId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM cassyx_connection WHERE LOWER(name) = LOWER(?) AND id <> ?",
            Integer.class,
            name,
            excludingId == null ? "" : excludingId);
    return count != null && count > 0;
  }

  /** @throws DuplicateConnectionNameException if the unique index on {@code name} rejects the row */
  public ConnectionRow insert(ConnectionRow row) {
    try {
      jdbc.update(
          """
          INSERT INTO cassyx_connection (
            id, name, mode, description, contact_points, local_datacenter, protocol_version_name,
            username, password_cipher, password_iv, default_keyspace, request_timeout_millis, tags,
            advanced_config,
            astra_database_id, astra_database_name, astra_token_cipher, astra_token_iv,
            scb_acquisition_mode, scb_region, scb_type, scb_custom_domain, scb_path,
            scb_bundle_cipher, scb_bundle_iv, scb_bundle_fetched_at, scb_file_name, scb_size_bytes,
            scb_sha256, scb_source, scb_cache_key, scb_validated,
            ssl_enabled, ssl_hostname_validation, ssl_cipher_suites,
            truststore_cipher, truststore_iv, truststore_password_cipher, truststore_password_iv,
            truststore_file_name, truststore_type,
            keystore_cipher, keystore_iv, keystore_password_cipher, keystore_password_iv,
            keystore_file_name, keystore_type,
            ssh_enabled, ssh_host, ssh_port, ssh_username,
            ssh_password_cipher, ssh_password_iv,
            ssh_private_key_cipher, ssh_private_key_iv,
            ssh_private_key_passphrase_cipher, ssh_private_key_passphrase_iv,
            ssh_local_port, ssh_remote_host, ssh_remote_port, ssh_strict_host_key_checking,
            ssh_known_hosts_entry,
            created_at, updated_at, last_connected_at
          ) VALUES (
            ?, ?, ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?, ?,
            ?,
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?,
            ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?,
            ?, ?, ?
          )
          """,
          parameters(row));
      return row;
    } catch (DuplicateKeyException e) {
      throw new DuplicateConnectionNameException(row.name(), e);
    }
  }

  /** @return false when the id does not exist, so the caller can answer 404 rather than guess */
  public boolean update(ConnectionRow row) {
    try {
      int updated =
          jdbc.update(
              """
              UPDATE cassyx_connection SET
                name = ?, mode = ?, description = ?, contact_points = ?, local_datacenter = ?,
                protocol_version_name = ?, username = ?, password_cipher = ?, password_iv = ?,
                default_keyspace = ?, request_timeout_millis = ?, tags = ?, advanced_config = ?,
                astra_database_id = ?, astra_database_name = ?, astra_token_cipher = ?,
                astra_token_iv = ?, scb_acquisition_mode = ?, scb_region = ?, scb_type = ?,
                scb_custom_domain = ?, scb_path = ?, scb_bundle_cipher = ?, scb_bundle_iv = ?,
                scb_bundle_fetched_at = ?, scb_file_name = ?, scb_size_bytes = ?, scb_sha256 = ?,
                scb_source = ?, scb_cache_key = ?, scb_validated = ?,
                ssl_enabled = ?, ssl_hostname_validation = ?, ssl_cipher_suites = ?,
                truststore_cipher = ?, truststore_iv = ?, truststore_password_cipher = ?,
                truststore_password_iv = ?, truststore_file_name = ?, truststore_type = ?,
                keystore_cipher = ?, keystore_iv = ?, keystore_password_cipher = ?,
                keystore_password_iv = ?, keystore_file_name = ?, keystore_type = ?,
                ssh_enabled = ?, ssh_host = ?, ssh_port = ?, ssh_username = ?,
                ssh_password_cipher = ?, ssh_password_iv = ?,
                ssh_private_key_cipher = ?, ssh_private_key_iv = ?,
                ssh_private_key_passphrase_cipher = ?, ssh_private_key_passphrase_iv = ?,
                ssh_local_port = ?, ssh_remote_host = ?, ssh_remote_port = ?,
                ssh_strict_host_key_checking = ?, ssh_known_hosts_entry = ?,
                updated_at = ?, last_connected_at = ?
              WHERE id = ?
              """,
              updateParameters(row));
      return updated > 0;
    } catch (DuplicateKeyException e) {
      throw new DuplicateConnectionNameException(row.name(), e);
    }
  }

  public boolean delete(String id) {
    return jdbc.update("DELETE FROM cassyx_connection WHERE id = ?", id) > 0;
  }

  public void touchLastConnected(String id, Instant at) {
    jdbc.update(
        "UPDATE cassyx_connection SET last_connected_at = ? WHERE id = ?", Timestamp.from(at), id);
  }

  private static Object[] parameters(ConnectionRow row) {
    Object[] common = commonParameters(row);
    Object[] all = new Object[common.length + 4];
    all[0] = row.id();
    System.arraycopy(common, 0, all, 1, common.length);
    all[common.length + 1] = timestamp(row.createdAt());
    all[common.length + 2] = timestamp(row.updatedAt());
    all[common.length + 3] = timestamp(row.lastConnectedAt());
    return all;
  }

  private static Object[] updateParameters(ConnectionRow row) {
    Object[] common = commonParameters(row);
    Object[] all = new Object[common.length + 3];
    System.arraycopy(common, 0, all, 0, common.length);
    all[common.length] = timestamp(row.updatedAt());
    all[common.length + 1] = timestamp(row.lastConnectedAt());
    all[common.length + 2] = row.id();
    return all;
  }

  /** The column list shared by INSERT and UPDATE, in declaration order. */
  private static Object[] commonParameters(ConnectionRow row) {
    return new Object[] {
      row.name(),
      row.mode(),
      row.description(),
      row.contactPoints(),
      row.localDatacenter(),
      row.protocolVersionName(),
      row.username(),
      cipher(row.password()),
      nonce(row.password()),
      row.defaultKeyspace(),
      row.requestTimeoutMillis(),
      row.tags(),
      row.advancedConfig(),
      row.astraDatabaseId(),
      row.astraDatabaseName(),
      cipher(row.astraToken()),
      nonce(row.astraToken()),
      row.scbAcquisitionMode(),
      row.scbRegion(),
      row.scbType(),
      row.scbCustomDomain(),
      row.scbPath(),
      cipher(row.scbBundle()),
      nonce(row.scbBundle()),
      timestamp(row.scbBundleFetchedAt()),
      row.scbFileName(),
      row.scbSizeBytes(),
      row.scbSha256(),
      row.scbSource(),
      row.scbCacheKey(),
      row.scbValidated(),
      row.sslEnabled(),
      row.sslHostnameValidation(),
      row.sslCipherSuites(),
      cipher(row.truststore()),
      nonce(row.truststore()),
      cipher(row.truststorePassword()),
      nonce(row.truststorePassword()),
      row.truststoreFileName(),
      row.truststoreType(),
      cipher(row.keystore()),
      nonce(row.keystore()),
      cipher(row.keystorePassword()),
      nonce(row.keystorePassword()),
      row.keystoreFileName(),
      row.keystoreType(),
      row.sshEnabled(),
      row.sshHost(),
      row.sshPort(),
      row.sshUsername(),
      cipher(row.sshPassword()),
      nonce(row.sshPassword()),
      cipher(row.sshPrivateKey()),
      nonce(row.sshPrivateKey()),
      cipher(row.sshPrivateKeyPassphrase()),
      nonce(row.sshPrivateKeyPassphrase()),
      row.sshLocalPort(),
      row.sshRemoteHost(),
      row.sshRemotePort(),
      row.sshStrictHostKeyChecking(),
      row.sshKnownHostsEntry()
    };
  }

  private static byte[] cipher(EncryptedValue value) {
    return value == null ? null : value.ciphertext();
  }

  private static byte[] nonce(EncryptedValue value) {
    return value == null ? null : value.nonce();
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static final RowMapper<ConnectionRow> MAPPER =
      (rs, rowNum) ->
          new ConnectionRow()
              .id(rs.getString("id"))
              .name(rs.getString("name"))
              .mode(rs.getString("mode"))
              .description(rs.getString("description"))
              .contactPoints(rs.getString("contact_points"))
              .localDatacenter(rs.getString("local_datacenter"))
              .protocolVersionName(rs.getString("protocol_version_name"))
              .username(rs.getString("username"))
              .password(encrypted(rs, "password_iv", "password_cipher"))
              .defaultKeyspace(rs.getString("default_keyspace"))
              .requestTimeoutMillis(integer(rs, "request_timeout_millis"))
              .tags(rs.getString("tags"))
              .advancedConfig(rs.getString("advanced_config"))
              .astraDatabaseId(rs.getString("astra_database_id"))
              .astraDatabaseName(rs.getString("astra_database_name"))
              .astraToken(encrypted(rs, "astra_token_iv", "astra_token_cipher"))
              .scbAcquisitionMode(rs.getString("scb_acquisition_mode"))
              .scbRegion(rs.getString("scb_region"))
              .scbType(rs.getString("scb_type"))
              .scbCustomDomain(rs.getString("scb_custom_domain"))
              .scbPath(rs.getString("scb_path"))
              .scbBundle(encrypted(rs, "scb_bundle_iv", "scb_bundle_cipher"))
              .scbBundleFetchedAt(instant(rs, "scb_bundle_fetched_at"))
              .scbFileName(rs.getString("scb_file_name"))
              .scbSizeBytes(longValue(rs, "scb_size_bytes"))
              .scbSha256(rs.getString("scb_sha256"))
              .scbSource(rs.getString("scb_source"))
              .scbCacheKey(rs.getString("scb_cache_key"))
              .scbValidated(rs.getBoolean("scb_validated"))
              .sslEnabled(rs.getBoolean("ssl_enabled"))
              .sslHostnameValidation(rs.getBoolean("ssl_hostname_validation"))
              .sslCipherSuites(rs.getString("ssl_cipher_suites"))
              .truststore(encrypted(rs, "truststore_iv", "truststore_cipher"))
              .truststorePassword(
                  encrypted(rs, "truststore_password_iv", "truststore_password_cipher"))
              .truststoreFileName(rs.getString("truststore_file_name"))
              .truststoreType(rs.getString("truststore_type"))
              .keystore(encrypted(rs, "keystore_iv", "keystore_cipher"))
              .keystorePassword(encrypted(rs, "keystore_password_iv", "keystore_password_cipher"))
              .keystoreFileName(rs.getString("keystore_file_name"))
              .keystoreType(rs.getString("keystore_type"))
              .sshEnabled(rs.getBoolean("ssh_enabled"))
              .sshHost(rs.getString("ssh_host"))
              .sshPort(integer(rs, "ssh_port"))
              .sshUsername(rs.getString("ssh_username"))
              .sshPassword(encrypted(rs, "ssh_password_iv", "ssh_password_cipher"))
              .sshPrivateKey(encrypted(rs, "ssh_private_key_iv", "ssh_private_key_cipher"))
              .sshPrivateKeyPassphrase(
                  encrypted(
                      rs, "ssh_private_key_passphrase_iv", "ssh_private_key_passphrase_cipher"))
              .sshLocalPort(integer(rs, "ssh_local_port"))
              .sshRemoteHost(rs.getString("ssh_remote_host"))
              .sshRemotePort(integer(rs, "ssh_remote_port"))
              .sshStrictHostKeyChecking(rs.getBoolean("ssh_strict_host_key_checking"))
              .sshKnownHostsEntry(rs.getString("ssh_known_hosts_entry"))
              .createdAt(instant(rs, "created_at"))
              .updatedAt(instant(rs, "updated_at"))
              .lastConnectedAt(instant(rs, "last_connected_at"));

  /** A credential is present only when BOTH halves are - ciphertext without its nonce is garbage. */
  private static EncryptedValue encrypted(ResultSet rs, String ivColumn, String cipherColumn)
      throws SQLException {
    byte[] nonce = rs.getBytes(ivColumn);
    byte[] ciphertext = rs.getBytes(cipherColumn);
    return nonce == null || ciphertext == null ? null : new EncryptedValue(nonce, ciphertext);
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static Long longValue(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
