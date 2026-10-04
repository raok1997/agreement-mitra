package in.agreementmitra;

import in.agreementmitra.AbuseLimitsProperties.ClassLimits;
import in.agreementmitra.RouteClassifier.Classification;
import in.agreementmitra.SlidingWindowRateLimiter.Decision;
import in.agreementmitra.SlidingWindowRateLimiter.Limit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the route-class rate limits (anonymous-surface-abuse-controls D4).
 *
 * <p>Registered <b>inside</b> the security chain, after {@code CsrfFilter} and {@link
 * CrossSiteRequestGuard}, and nowhere else (its servlet registration is disabled). Each neighbour
 * is deliberate:
 *
 * <ul>
 *   <li><b>After CSRF</b>, so an unsafe request without a valid token is refused before it is
 *       counted. Otherwise any page a victim visits could fire cross-site POSTs that burn the
 *       victim's -- and their whole carrier NAT's -- budget.
 *   <li><b>Before the session lookup</b>, so a flood of requests carrying junk session cookies is
 *       bounded before it reaches Postgres.
 *   <li><b>Inside the chain</b>, so the path is the firewall-normalised one and a refusal carries
 *       the chain's security headers.
 * </ul>
 *
 * <p>Only the {@code REQUEST} dispatch is counted ({@link OncePerRequestFilter} skips the {@code
 * /error} re-dispatch), so an error never double-counts.
 *
 * <p>The refusal is written here, because a filter runs outside {@code DispatcherServlet} and the
 * exception handler never sees it: {@code 429} problem+json with a fixed body, {@code instance}
 * pinned to the type (the path is never echoed) and {@code Retry-After} in seconds. It is the same
 * whether or not the named agreement exists, so a limit is not an existence oracle.
 */
final class RouteRateLimitFilter extends OncePerRequestFilter {

  static final String TYPE = "urn:agreementmitra:problem:rate-limited";

  private static final String BODY =
      "{\"type\":\""
          + TYPE
          + "\",\"title\":\"Too many requests\",\"status\":429,"
          + "\"detail\":\"Too many requests. Wait a moment and try again.\",\"instance\":\""
          + TYPE
          + "\"}";

  private record ClassLimit(Limit perSource, Limit perResource) {}

  private final boolean enabled;
  private final Map<RouteClass, ClassLimit> limits = new EnumMap<>(RouteClass.class);
  private final SlidingWindowRateLimiter limiter;
  private final ClientSourceResolver sources;
  private final SecurityEvents events;

  RouteRateLimitFilter(
      AbuseLimitsProperties properties,
      SlidingWindowRateLimiter limiter,
      ClientSourceResolver sources,
      SecurityEvents events) {
    this.enabled = properties.enabled();
    properties.classes().forEach((routeClass, c) -> limits.put(routeClass, toLimit(c)));
    this.limiter = limiter;
    this.sources = sources;
    this.events = events;
  }

  private static ClassLimit toLimit(ClassLimits c) {
    Limit perSource = new Limit(c.perSource(), c.window(), c.lockout());
    // The per-resource dimension refuses within its window but never locks an agreement out.
    Limit perResource =
        c.perResource() == null ? null : new Limit(c.perResource(), c.window(), Duration.ZERO);
    return new ClassLimit(perSource, perResource);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !enabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    Classification classification =
        RouteClassifier.classify(request.getMethod(), path, request.getHeader(HttpHeaders.ACCEPT));
    RouteClass routeClass = classification.routeClass();
    if (!routeClass.limited()) {
      chain.doFilter(request, response);
      return;
    }

    ClassLimit limit = limits.get(routeClass);
    ClientSource source = sources.resolve(request);
    Decision decision =
        limiter.tryAcquire(
            routeClass.label(),
            source.key(),
            limit.perSource(),
            classification.resource(),
            limit.perResource());
    // Only the source dimension locks out here; the per-resource one never does (D5).
    if (decision.sourceLockout() > 0) {
      events.lockout(classification.route(), routeClass.label(), source, decision.sourceLockout());
    }
    if (decision.allowed()) {
      chain.doFilter(request, response);
      return;
    }
    refuse(response, decision.retryAfter());
  }

  private static void refuse(HttpServletResponse response, Duration retryAfter) throws IOException {
    if (response.isCommitted()) {
      return;
    }
    long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }
}
