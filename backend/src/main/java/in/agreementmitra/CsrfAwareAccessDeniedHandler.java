package in.agreementmitra;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Makes a CSRF refusal distinguishable from an authorization refusal (cookie-session-auth D3), so
 * the SPA can retry once on exactly this case and never on a role/ownership 403.
 *
 * <p>A {@link CsrfException} (missing or invalid token) gets {@code 403 application/problem+json}
 * of type {@value #TYPE} with a FIXED title and detail. The exception message is never written to
 * the body or a log: {@code InvalidCsrfTokenException}'s message embeds the attacker-supplied
 * header value. Every other {@link AccessDeniedException} is delegated unchanged to {@link
 * AccessDeniedHandlerImpl}, so the existing bare 403s stay byte-for-byte as before.
 */
final class CsrfAwareAccessDeniedHandler implements AccessDeniedHandler {

  static final String TYPE = "urn:agreementmitra:problem:csrf";

  private static final String BODY =
      "{\"type\":\""
          + TYPE
          + "\",\"title\":\"CSRF token missing or invalid\",\"status\":403,"
          + "\"detail\":\"The request was refused because its CSRF token was missing or did not"
          + " match. Reload and try again.\",\"instance\":\""
          + TYPE
          + "\"}";

  private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    if (!(accessDeniedException instanceof CsrfException)) {
      delegate.handle(request, response, accessDeniedException);
      return;
    }
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(BODY);
  }
}
