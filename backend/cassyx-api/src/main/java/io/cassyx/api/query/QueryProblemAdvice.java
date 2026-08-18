package io.cassyx.api.query;

import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import io.cassyx.api.data.DataController;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.query.IncompletePrimaryKeyException;
import io.cassyx.core.api.query.PageTokenMismatchException;
import io.cassyx.core.api.query.ResultHandleExpiredException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 {@code application/problem+json} for the {@code query} and {@code data} tags.
 *
 * <p>Deliberately scoped to this workstream's controllers ({@code assignableTypes}) rather than
 * registered globally: eight workstreams are landing advices in parallel, and a global handler for
 * {@code RuntimeException} from any one of them would quietly swallow every other workstream's
 * errors.
 *
 * <p>Each mapping exists because the client does something different with it - a {@code 404} on a
 * result handle means "re-run the query", a {@code 422} on a primary key means "add these columns to
 * the SELECT". Collapsing them into 500s would throw that away.
 */
@RestControllerAdvice(
    assignableTypes = {QueryController.class, QueryLibraryController.class, DataController.class})
public class QueryProblemAdvice {

  private static final String BASE = "https://cassyx.dev/problems/";

  @ExceptionHandler(ResultHandleExpiredException.class)
  public ResponseEntity<ProblemDetail> resultHandleExpired(
      ResultHandleExpiredException e, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Result set expired", "result-handle-expired", e.getMessage(), request);
  }

  @ExceptionHandler(PageTokenMismatchException.class)
  public ResponseEntity<ProblemDetail> pageTokenMismatch(
      PageTokenMismatchException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Page token mismatch", "page-token-mismatch", e.getMessage(), request);
  }

  @ExceptionHandler(IncompletePrimaryKeyException.class)
  public ResponseEntity<ProblemDetail> incompletePrimaryKey(
      IncompletePrimaryKeyException e, HttpServletRequest request) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Incomplete primary key",
            "incomplete-primary-key",
            e.getMessage(),
            request);
    response.getBody().setProperty("missingKeyColumns", e.missingKeyColumns());
    return response;
  }

  @ExceptionHandler(ConnectionNotOpenException.class)
  public ResponseEntity<ProblemDetail> notConnected(
      ConnectionNotOpenException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Not connected", "not-connected", e.getMessage(), request);
  }

  @ExceptionHandler(CqlExecutionException.class)
  public ResponseEntity<ProblemDetail> cqlError(CqlExecutionException e, HttpServletRequest request) {
    HttpStatus status =
        e.getCause() instanceof DriverTimeoutException
            ? HttpStatus.GATEWAY_TIMEOUT
            : HttpStatus.UNPROCESSABLE_ENTITY;
    ResponseEntity<ProblemDetail> response =
        problem(status, "CQL execution failed", "cql-error", e.getMessage(), request);
    response.getBody().setProperty("cql", e.cql());
    response.getBody().setProperty("cqlErrorClass", e.errorClass());
    return response;
  }

  @ExceptionHandler(QueryValidationException.class)
  public ResponseEntity<ProblemDetail> queryValidation(
      QueryValidationException e, HttpServletRequest request) {
    ResponseEntity<ProblemDetail> response =
        problem(HttpStatus.UNPROCESSABLE_ENTITY, "CQL execution failed", "cql-error", e.getMessage(), request);
    response.getBody().setProperty("cqlErrorClass", e.getClass().getName());
    return response;
  }

  @ExceptionHandler(DriverTimeoutException.class)
  public ResponseEntity<ProblemDetail> timeout(DriverTimeoutException e, HttpServletRequest request) {
    return problem(
        HttpStatus.GATEWAY_TIMEOUT, "Query timed out", "query-timeout", e.getMessage(), request);
  }

  @ExceptionHandler({CassyxCoreException.class, IllegalArgumentException.class})
  public ResponseEntity<ProblemDetail> badRequest(RuntimeException e, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Bad request", "bad-request", e.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> validation(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    ResponseEntity<ProblemDetail> response =
        problem(
            HttpStatus.BAD_REQUEST,
            "Request validation failed",
            "validation-failed",
            e.getBindingResult().getErrorCount() + " field(s) are invalid.",
            request);
    List<FieldProblem> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldProblem(error.getField(), error.getDefaultMessage()))
            .toList();
    response.getBody().setProperty("errors", errors);
    return response;
  }

  /** Contract: {@code FieldError}. {@code rejectedValue} is never echoed - it may be a secret. */
  public record FieldProblem(String field, String message) {}

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String title, String type, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(BASE + type));
    problem.setTitle(title);
    problem.setDetail(detail);
    if (request != null) {
      problem.setInstance(URI.create(request.getRequestURI()));
    }
    return ResponseEntity.status(status).body(problem);
  }
}
