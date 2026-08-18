package io.cassyx.api.connections;

import io.cassyx.api.capabilities.CapabilitiesController;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.SecretCipherException;
import io.cassyx.core.api.astra.AstraDevOpsException;
import io.cassyx.core.api.astra.ScbPathException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * RFC 9457 {@code application/problem+json} for the connections and capabilities endpoints.
 *
 * <p>Scoped to this workstream's controllers with {@code assignableTypes} rather than declared
 * globally: eight workstreams are building against this contract in parallel and a global advice
 * from any one of them would silently change every other one's error shapes.
 *
 * <p><b>No handler here echoes a request body.</b> That is the rule that keeps passwords and Astra
 * tokens out of error responses - the place they most often leak, because "include the request for
 * context" is such a natural thing to write.
 */
@RestControllerAdvice(
    assignableTypes = {ConnectionsController.class, AstraController.class, CapabilitiesController.class})
public class ConnectionsExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionsExceptionHandler.class);
  private static final String PROBLEM_BASE = "https://cassyx.dev/problems/";

  @ExceptionHandler(ConnectionNotFoundException.class)
  public ProblemDetail handleNotFound(ConnectionNotFoundException e) {
    return problem(
        HttpStatus.NOT_FOUND,
        "not-found",
        "Not found",
        "No connection " + e.connectionId() + ".");
  }

  /** 409 with a Connect affordance, not 404: the connection exists, it just has no live session. */
  @ExceptionHandler(ConnectionNotOpenException.class)
  public ProblemDetail handleNotConnected(ConnectionNotOpenException e) {
    return problem(
        HttpStatus.CONFLICT,
        "not-connected",
        "Not connected",
        "This connection has no live session. Connect it first.");
  }

  @ExceptionHandler(DuplicateConnectionNameException.class)
  public ProblemDetail handleDuplicate(DuplicateConnectionNameException e) {
    return problem(HttpStatus.CONFLICT, "conflict", "Already exists", e.getMessage());
  }

  /** Field-level failures so the form can highlight the right input, not show a banner. */
  @ExceptionHandler(ConnectionValidationException.class)
  public ProblemDetail handleValidation(ConnectionValidationException e) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Request validation failed",
            e.getMessage());
    problem.setProperty(
        "errors", List.of(Map.of("field", e.field(), "message", e.getMessage())));
    return problem;
  }

  /**
   * A bad {@code scbType} (most often the phantom {@code "region"}) and other malformed enum values.
   *
   * <p>{@link io.cassyx.api.connections.dto.ScbType#parse} builds the message, and it names the two
   * legal values and points at the separate {@code region} field - which is the actual fix.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", e.getMessage());
  }

  /**
   * A rejected server-side bundle path or an invalid bundle.
   *
   * <p>{@code 400} for a path that escapes {@code CASSYX_SCB_PATH_ROOT} and {@code 422} for a file
   * that is not a bundle: one is the caller asking for something they may not have, the other is
   * them giving us the wrong file.
   */
  @ExceptionHandler(ScbPathException.class)
  public ProblemDetail handleScbPath(ScbPathException e) {
    boolean notABundle = e.getMessage() != null && e.getMessage().contains("not a secure connect bundle");
    return problem(
        notABundle ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST,
        notABundle ? "invalid-secure-connect-bundle" : "invalid-scb-path",
        notABundle ? "Not a secure connect bundle" : "Rejected bundle path",
        e.getMessage());
  }

  /**
   * DevOps API failures.
   *
   * <p>{@code statusCode() == 0} means we never reached Astra at all, which is a {@code 502} whose
   * message points at manual upload - many self-hosted installs have no egress, and silently
   * retrying would just hang the UI (plan section 3.1, deviation 6).
   */
  @ExceptionHandler(AstraDevOpsException.class)
  public ProblemDetail handleAstra(AstraDevOpsException e) {
    int status = e.statusCode();
    if (status == 401 || status == 403) {
      return problem(
          HttpStatus.UNAUTHORIZED, "astra-unauthorized", "Astra token rejected", e.getMessage());
    }
    if (status == 404) {
      return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
    }
    return problem(
        HttpStatus.BAD_GATEWAY, "astra-unreachable", "Astra DevOps API unreachable", e.getMessage());
  }

  /**
   * A credential that will not decrypt - almost always a changed {@code CASSYX_SECRET_KEY}.
   *
   * <p>{@code 500}, because it is a server misconfiguration rather than anything the caller did,
   * and the message says what to do. The cause is logged; the response carries no cryptographic
   * detail at all.
   */
  @ExceptionHandler(SecretCipherException.class)
  public ProblemDetail handleCipher(SecretCipherException e) {
    LOG.error("Credential encryption failure", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "credential-unreadable",
        "Stored credential unreadable",
        e.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ProblemDetail handleTooLarge(MaxUploadSizeExceededException e) {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "payload-too-large",
        "Upload too large",
        "That file exceeds the configured maximum upload size.");
  }

  /** Everything cassyx-core raises that has no more specific mapping: a failed connect. */
  @ExceptionHandler(CassyxCoreException.class)
  public ProblemDetail handleCore(CassyxCoreException e) {
    return problem(
        HttpStatus.BAD_GATEWAY, "connection-failed", "Could not reach cluster", e.getMessage());
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(PROBLEM_BASE + type));
    problem.setTitle(title);
    return problem;
  }
}
