package io.cassyx.api.connections;

import io.cassyx.api.connections.dto.ConnectionHealth;
import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionResponse;
import io.cassyx.api.connections.dto.ConnectionTestRequest;
import io.cassyx.api.connections.dto.ConnectionTestResult;
import io.cassyx.api.connections.dto.ScbMode;
import io.cassyx.api.connections.dto.SessionState;
import io.cassyx.core.api.EncryptedValue;
import io.cassyx.core.api.SecretCipher;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Connection CRUD, uploads and session lifecycle - the {@code connections} tag of the contract.
 *
 * <p>A thin adapter: validation, persistence, crypto and session building all live below it, so
 * this class is mostly about status codes. Two of those are load-bearing and deliberate:
 *
 * <ul>
 *   <li>{@code POST /connect} on an already-connected connection is a {@code 200} with the existing
 *       state, not a conflict. Reconnecting is what a user does when they are unsure, and it must
 *       not churn the session.
 *   <li>{@code POST /test} returns {@code 200} even for a failed probe. The whole value of a Test
 *       button is the diagnostic, and a 502 gets swallowed by generic client-side error handling.
 * </ul>
 */
@RestController
public class ConnectionsController {

  private final ConnectionService connections;
  private final ConnectionSessionService sessions;
  private final SecureBundleService bundles;
  private final SecretCipher cipher;

  public ConnectionsController(
      ConnectionService connections,
      ConnectionSessionService sessions,
      SecureBundleService bundles,
      SecretCipher cipher) {
    this.connections = connections;
    this.sessions = sessions;
    this.bundles = bundles;
    this.cipher = cipher;
  }

  /* ------------------------------------------------------------------ CRUD */

  @GetMapping("/api/connections")
  public List<ConnectionResponse> listConnections() {
    return connections.list();
  }

  @PostMapping("/api/connections")
  public ResponseEntity<ConnectionResponse> createConnection(
      @Valid @RequestBody ConnectionRequest request) {
    ConnectionResponse created = connections.create(request);
    return ResponseEntity.created(URI.create("/api/connections/" + created.id())).body(created);
  }

  @GetMapping("/api/connections/{connectionId}")
  public ConnectionResponse getConnection(@PathVariable String connectionId) {
    return connections.get(connectionId);
  }

  @PutMapping("/api/connections/{connectionId}")
  public ConnectionResponse updateConnection(
      @PathVariable String connectionId, @Valid @RequestBody ConnectionRequest request) {
    return connections.update(connectionId, request);
  }

  /** Closes any live session first: deleting a connection out from under its session leaks it. */
  @DeleteMapping("/api/connections/{connectionId}")
  public ResponseEntity<Void> deleteConnection(@PathVariable String connectionId) {
    sessions.disconnect(connectionId);
    connections.delete(connectionId);
    return ResponseEntity.noContent().build();
  }

  /* ------------------------------------------------------------------ uploads */

  @PostMapping(
      value = "/api/connections/{connectionId}/secure-connect-bundle",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ConnectionResponse uploadSecureConnectBundle(
      @PathVariable String connectionId, @RequestParam("file") MultipartFile file) {
    ConnectionRow row = connections.require(connectionId);
    bundles.store(row, readBytes(file), file.getOriginalFilename(), ScbMode.UPLOAD, null);
    row.scbAcquisitionMode(ScbMode.UPLOAD.name());
    return connections.save(row);
  }

  @DeleteMapping("/api/connections/{connectionId}/secure-connect-bundle")
  public ResponseEntity<Void> deleteSecureConnectBundle(@PathVariable String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    bundles.remove(row);
    connections.save(row);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/api/connections/{connectionId}/ssl/truststore",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ConnectionResponse uploadTruststore(
      @PathVariable String connectionId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "password", required = false) String password,
      @RequestParam(value = "storeType", required = false) String storeType) {
    ConnectionRow row = connections.require(connectionId);
    row.truststore(encrypt(readBytes(file)))
        .truststoreFileName(file.getOriginalFilename())
        .truststoreType(storeType == null ? "PKCS12" : storeType)
        .sslEnabled(true);
    if (password != null) {
      row.truststorePassword(password.isEmpty() ? null : encryptString(password));
    }
    return connections.save(row);
  }

  @DeleteMapping("/api/connections/{connectionId}/ssl/truststore")
  public ResponseEntity<Void> deleteTruststore(@PathVariable String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    row.truststore(null).truststorePassword(null).truststoreFileName(null).truststoreType(null);
    connections.save(row);
    return ResponseEntity.noContent().build();
  }

  @PostMapping(
      value = "/api/connections/{connectionId}/ssl/keystore",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ConnectionResponse uploadKeystore(
      @PathVariable String connectionId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "password", required = false) String password,
      @RequestParam(value = "storeType", required = false) String storeType) {
    ConnectionRow row = connections.require(connectionId);
    row.keystore(encrypt(readBytes(file)))
        .keystoreFileName(file.getOriginalFilename())
        .keystoreType(storeType == null ? "PKCS12" : storeType)
        .sslEnabled(true);
    if (password != null) {
      row.keystorePassword(password.isEmpty() ? null : encryptString(password));
    }
    return connections.save(row);
  }

  @DeleteMapping("/api/connections/{connectionId}/ssl/keystore")
  public ResponseEntity<Void> deleteKeystore(@PathVariable String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    row.keystore(null).keystorePassword(null).keystoreFileName(null).keystoreType(null);
    connections.save(row);
    return ResponseEntity.noContent().build();
  }

  /* ------------------------------------------------------------------ sessions */

  @PostMapping("/api/connections/{connectionId}/connect")
  public SessionState connectConnection(@PathVariable String connectionId) {
    return sessions.connect(connectionId);
  }

  @PostMapping("/api/connections/{connectionId}/disconnect")
  public SessionState disconnectConnection(@PathVariable String connectionId) {
    return sessions.disconnect(connectionId);
  }

  @GetMapping("/api/connections/{connectionId}/health")
  public ConnectionHealth getConnectionHealth(@PathVariable String connectionId) {
    return sessions.health(connectionId);
  }

  @GetMapping("/api/sessions")
  public List<SessionState> listSessions() {
    return sessions.sessions();
  }

  @PostMapping("/api/connections/test")
  public ConnectionTestResult testConnection(@Valid @RequestBody ConnectionTestRequest request) {
    if (!request.isSaved() && request.connection() == null) {
      throw new ConnectionValidationException(
          "connection", "supply either connectionId or connection");
    }
    return sessions.test(request.connectionId(), request.connection());
  }

  /* ------------------------------------------------------------------ helpers */

  private EncryptedValue encrypt(byte[] bytes) {
    return cipher.encrypt(bytes);
  }

  private EncryptedValue encryptString(String value) {
    return cipher.encryptString(value);
  }

  private static byte[] readBytes(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ConnectionValidationException("file", "the uploaded file is empty");
    }
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read the uploaded file", e);
    }
  }
}
