package in.agreementmitra;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * The SPA double-submit CSRF request handler (cookie-session-auth D3/D4), departing from Spring's
 * reference SPA recipe in two deliberate ways:
 *
 * <ul>
 *   <li><b>Header-only resolution.</b> The token is read from the {@code X-XSRF-TOKEN} header only,
 *       never the {@code _csrf} parameter -- that fallback makes {@code CsrfFilter} call {@code
 *       getParameter}, which parses form/multipart bodies (draft upload is ~11 MB) before
 *       authorization. The SPA always sends the header and there are no server-rendered forms.
 *   <li><b>No Xor masking.</b> BREACH masking only protects a token rendered into a compressed
 *       body; nothing renders one. Reintroduce Xor if that ever changes.
 * </ul>
 *
 * <p>{@code setCsrfRequestAttributeName(null)} opts out of deferred tokens: {@code handle} (which
 * {@code CsrfFilter} runs on every request, before the protection check) loads the token, so any
 * response to a browser without the CSRF cookie sets one -- including a CSRF refusal. That is
 * intentional (eager issuance); do not "fix" it.
 */
final class HeaderOnlyCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

  HeaderOnlyCsrfTokenRequestHandler() {
    setCsrfRequestAttributeName(null);
  }

  @Override
  public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
    return request.getHeader(csrfToken.getHeaderName());
  }
}
