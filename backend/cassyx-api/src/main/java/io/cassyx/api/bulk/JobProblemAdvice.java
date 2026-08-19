package io.cassyx.api.bulk;

import io.cassyx.api.bulk.JobService.JobCapExceededException;
import io.cassyx.api.bulk.JobService.JobNotFoundException;
import io.cassyx.api.bulk.JobService.JobStateException;
import io.cassyx.bulk.api.BulkException;
import io.cassyx.core.api.ConnectionNotOpenException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 9457 {@code application/problem+json} for the job substrate's own controllers.
 *
 * <p>Scoped with {@code assignableTypes} rather than registered globally: several workstreams are
 * landing advices in parallel, and a global handler for {@code RuntimeException} from any one of
 * them silently swallows every other workstream's errors.
 *
 * <p>Each status is a different instruction to the client, which is why they are not collapsed:
 * {@code 404} means the job is gone, {@code 409} means "you asked at the wrong point in the
 * lifecycle - cancel it first, or wait for it to succeed", and {@code 429} means "retry when a slot
 * frees up" rather than "your request was wrong".
 */
@RestControllerAdvice(
    assignableTypes = {JobController.class, UnloadJobController.class, CountJobController.class})
public class JobProblemAdvice {

  private static final String BASE = "https://cassyx.dev/problems/";

  /**
   * A statistics mode this table or target cannot produce (plan sections 5.4, 7.1).
   *
   * <p>422, and the refused modes are named in an extension member so the UI can retry with exactly
   * the rest rather than making the user guess which one was the problem.
   */
  @ExceptionHandler(CountModeUnsupportedException.class)
  public ResponseEntity<ProblemDetail> unsupportedCountMode(
      CountModeUnsupportedException e, HttpServletRequest request) {
    ResponseEntity<ProblemDetail> response = problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Statistics mode not supported",
        "count-mode-unsupported",
        e.getMessage(),
        request);
    ProblemDetail body = response.getBody();
    if (body != null) {
      body.setProperty("modes", e.modes());
    }
    return response;
  }

  @ExceptionHandler(JobNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(
      JobNotFoundException e, HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "Job not found", "job-not-found", e.getMessage(), request);
  }

  @ExceptionHandler(JobStateException.class)
  public ResponseEntity<ProblemDetail> conflict(JobStateException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Job state conflict", "job-state", e.getMessage(), request);
  }

  @ExceptionHandler(JobCapExceededException.class)
  public ResponseEntity<ProblemDetail> capExceeded(
      JobCapExceededException e, HttpServletRequest request) {
    return problem(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too many concurrent jobs",
        "job-cap-exceeded",
        e.getMessage(),
        request);
  }

  @ExceptionHandler(ConnectionNotOpenException.class)
  public ResponseEntity<ProblemDetail> notConnected(
      ConnectionNotOpenException e, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Not connected", "not-connected", e.getMessage(), request);
  }

  @ExceptionHandler({IllegalArgumentException.class, BulkException.class})
  public ResponseEntity<ProblemDetail> badRequest(RuntimeException e, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Bad request", "bad-request", e.getMessage(), request);
  }

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
