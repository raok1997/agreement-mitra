package in.agreementmitra;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Refuses an API request the browser itself marks as cross-site, before it is counted against any
 * rate limit (anonymous-surface-abuse-controls D4).
 *
 * <p>The limits are keyed on the client address, and CSRF tokens guard only unsafe methods. So
 * without this, any page a victim visits could embed a hundred {@code <img>} tags pointing at our
 * GET routes and spend the victim's -- and their whole carrier NAT's -- budget, locking them out of
 * their own status page and checkout. Browsers stamp every request with {@code Sec-Fetch-Site},
 * which a page cannot override; the SPA is same-origin, so a genuine call never says {@code
 * cross-site}.
 *
 * <p>Refusing rather than merely not counting is what makes the header safe to trust: a script
 * outside a browser can set it, but setting it only gets that script refused. A request without the
 * header (a non-browser client, an older browser) is unaffected and is limited normally.
 *
 * <p>Two routes are exempt because they are cross-site by nature: the Google sign-in callback (a
 * top-level navigation back from Google) and the vendor webhooks (server-to-server, authorized by
 * their own HMAC). Registered in the security chain after {@code CsrfFilter} and before the rate
 * limiter, and always on -- it is not part of the limits switch.
 */
final class CrossSiteRequestGuard extends OncePerRequestFilter {

  static final String TYPE = "urn:agreementmitra:problem:cross-site";

  private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

  private static final String BODY =
      "{\"type\":\""
          + TYPE
          + "\",\"title\":\"Cross-site request refused\",\"status\":403,"
          + "\"detail\":\"This request must come from the AgreementMitra site itself.\","
          + "\"instance\":\""
          + TYPE
          + "\"}";

  private record Exemption(HttpMethod method, PathPattern pattern) {}

  private static final List<Exemption> EXEMPT =
      List.of(
          new Exemption(
              HttpMethod.GET, PathPatternParser.defaultInstance.parse("/api/auth/google/callback")),
          new Exemption(
              HttpMethod.POST, PathPatternParser.defaultInstance.parse("/api/webhooks/esign")),
          new Exemption(
              HttpMethod.POST, PathPatternParser.defaultInstance.parse("/api/webhooks/razorpay")));

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!"cross-site".equalsIgnoreCase(request.getHeader(SEC_FETCH_SITE))) {
      return true;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/")) {
      return true;
    }
    PathContainer container = PathContainer.parsePath(path);
    return EXEMPT.stream()
        .anyMatch(e -> e.method().matches(request.getMethod()) && e.pattern().matches(container));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }
}
