package io.cassyx.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.license.LicenseGate;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Server-side licence enforcement (plan section 9.1): every {@code /api/**} request except the
 * three ungated paths is refused with {@code 402 Payment Required} unless {@link LicenseGate} says
 * the instance is unlocked.
 *
 * <p><b>Why a plain filter and not Spring Security.</b> Spring Security's value is authentication
 * and authorisation machinery, and cassyx has neither: plan section 12 records that it assumes a
 * single-user self-hosted instance, so there are no principals, no roles and no sessions for it to
 * manage. What it would add is surface - a filter chain, a servlet-context-wide security config,
 * and CSRF protection that the Stripe webhook under {@code /api/billing/**} would then have to be
 * explicitly exempted from, creating a brand new way to break payments while adding nothing to the
 * one decision actually being made here. This filter is that decision and nothing else. If real
 * user accounts ever arrive, Spring Security can replace it and reuse the same {@link LicenseGate}.
 *
 * <p>The gate is shared with {@code GET /api/license} rather than re-derived, so the two can never
 * disagree about whether this instance is licensed.
 */
public class LicenseGateFilter extends OncePerRequestFilter {

  /** Plan section 9.1 / section 2.3: exactly these, and their subtrees. */
  static final List<String> UNGATED_PREFIXES =
      List.of("/api/health", "/api/license", "/api/billing");

  private static final String API_PREFIX = "/api/";

  private static final String PROBLEM_TYPE = "https://cassyx.dev/problems/license-required";

  private final LicenseGate gate;
  private final ObjectMapper objectMapper;

  public LicenseGateFilter(LicenseGate gate, ObjectMapper objectMapper) {
    this.gate = gate;
    this.objectMapper = objectMapper;
  }

  /**
   * Non-{@code /api} traffic (the SPA bundle, static assets, {@code /actuator}) passes untouched:
   * gating it would stop the UI serving the very activation screen the 402 is telling the user to
   * go and use.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !isGated(pathWithinApplication(request));
  }

  /**
   * Request path with any servlet context path removed, so the rules below stay correct if cassyx is
   * ever deployed under a context other than {@code /}.
   */
  static String pathWithinApplication(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String context = request.getContextPath();
    if (context != null && !context.isEmpty() && uri.startsWith(context)) {
      String within = uri.substring(context.length());
      return within.isEmpty() ? "/" : within;
    }
    return uri;
  }

  static boolean isGated(String path) {
    if (!path.startsWith(API_PREFIX)) {
      return false;
    }
    for (String prefix : UNGATED_PREFIXES) {
      // Exact match or a genuine subtree only. A prefix test alone would also ungate
      // "/api/licenseholders", which is how an ungated-path list quietly grows a hole.
      if (path.equals(prefix) || path.startsWith(prefix + "/")) {
        return false;
      }
    }
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    LicenseStatus status;
    try {
      status = gate.status();
    } catch (RuntimeException e) {
      // Fail closed. The gate already swallows its own failures, so reaching this is a bug - and
      // the safe reading of a broken licence check is "not licensed".
      status = LicenseStatus.invalid("Licence verification failed", LicenseState.MALFORMED);
    }
    if (status.valid()) {
      chain.doFilter(request, response);
      return;
    }
    refuse(request, response, status);
  }

  /**
   * RFC 9457 {@code application/problem+json} (plan section 2.3), matching the shape the workstream
   * advices produce. {@code 402} rather than {@code 401}/{@code 403} because this is neither an
   * authentication nor an authorisation failure, and the client must be able to tell them apart.
   *
   * <p>{@code state} is carried as an extension member because the frontend renders a different
   * screen per state (expired, absent, upgrade) and a generic error would collapse all of them into
   * "something went wrong" - throwing away the only conversion moment the product gets.
   */
  private void refuse(HttpServletRequest request, HttpServletResponse response, LicenseStatus status)
      throws IOException {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYMENT_REQUIRED);
    problem.setType(URI.create(PROBLEM_TYPE));
    problem.setTitle("License required");
    problem.setDetail(detailFor(status));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("state", status.state() == null ? null : status.state().name());
    // Lets the activation screen offer purchase rather than an error for EXPIRED/ABSENT/UPGRADE
    // (plan section 9.4) without the client having to know the state taxonomy.
    problem.setProperty("invitesPurchase", status.invitesPurchase());
    problem.setProperty("unlockHint", gate.unlockAdvice());

    response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getOutputStream(), problem);
    response.flushBuffer();
  }

  private static String detailFor(LicenseStatus status) {
    String reason = status.reason();
    return reason == null || reason.isBlank()
        ? "This cassyx instance is not licensed."
        : "This cassyx instance is not licensed: " + reason;
  }
}
