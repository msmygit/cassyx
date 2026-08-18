package io.cassyx.api.schema;

import com.datastax.oss.driver.api.core.servererrors.AlreadyExistsException;
import com.datastax.oss.driver.api.core.servererrors.InvalidQueryException;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.SchemaNotFoundException;
import io.cassyx.core.api.schema.UnsupportedCapabilityException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 {@code application/problem+json} for every schema failure, per the contract's shared
 * {@code Problem} schema (plan section 2.3).
 *
 * <p>Scoped to this package rather than the whole application: eight workstreams are adding
 * controllers in parallel and a global advice would be a merge conflict waiting to happen.
 */
@RestControllerAdvice(basePackages = "io.cassyx.api.schema")
public class SchemaProblemAdvice {

  private static final String PROBLEM_BASE = "https://cassyx.dev/problems/";

  @ExceptionHandler(SchemaSessions.NotConnectedException.class)
  public ProblemDetail notConnected(SchemaSessions.NotConnectedException e) {
    return problem(HttpStatus.CONFLICT, "not-connected", "Not connected", e.getMessage());
  }

  @ExceptionHandler(SchemaNotFoundException.class)
  public ProblemDetail notFound(SchemaNotFoundException e) {
    ProblemDetail detail = problem(HttpStatus.NOT_FOUND, "not-found", "Not found", e.getMessage());
    if (e.identity() != null) {
      detail.setProperty("identity", e.identity());
    }
    return detail;
  }

  @ExceptionHandler(UnsupportedCapabilityException.class)
  public ProblemDetail unsupported(UnsupportedCapabilityException e) {
    ProblemDetail detail =
        problem(
            HttpStatus.NOT_IMPLEMENTED,
            "capability-unsupported",
            "Unsupported on this cluster",
            e.getMessage());
    detail.setProperty("capability", e.capability().wireName());
    return detail;
  }

  @ExceptionHandler(InvalidDefinitionException.class)
  public ProblemDetail invalidDefinition(InvalidDefinitionException e) {
    ProblemDetail detail =
        problem(
            HttpStatus.BAD_REQUEST,
            "validation-failed",
            "Request validation failed",
            "1 field is invalid.");
    detail.setProperty(
        "errors", List.of(Map.of("field", e.field() == null ? "definition" : e.field(),
            "message", e.getMessage())));
    return detail;
  }

  @ExceptionHandler(AlreadyExistsException.class)
  public ProblemDetail alreadyExists(AlreadyExistsException e) {
    return problem(HttpStatus.CONFLICT, "conflict", "Already exists", e.getMessage());
  }

  /**
   * The cluster rejected the statement. 422 rather than 400: the request was well-formed, the CQL
   * was not.
   */
  @ExceptionHandler({QueryValidationException.class, InvalidQueryException.class})
  public ProblemDetail cqlError(QueryValidationException e) {
    ProblemDetail detail =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "cql-error", "CQL execution failed", e.getMessage());
    detail.setProperty("cqlErrorClass", e.getClass().getName());
    return detail;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail illegalArgument(IllegalArgumentException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed", e.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(PROBLEM_BASE + type));
    problem.setTitle(title);
    problem.setDetail(detail);
    return problem;
  }
}
