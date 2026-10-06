package in.agreementmitra.support;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Calls the signing-request retry hatch, {@code POST /api/signing/{id}/request}, the only way it
 * can now be called: with a STAFF session (anonymous-surface-abuse-controls D7). The CSRF token
 * comes from the harness {@link CsrfTestInterceptor}, which merges it into the session cookie
 * header.
 *
 * <p>Customers never call this route -- signing starts server-side once the e-stamp is attached --
 * so a test that drives it is exercising the staff retry path.
 */
public final class SigningRequests {

  private static final String STAFF_SUBJECT = "signing-request-staff";

  private SigningRequests() {}

  /** POST the signing request for {@code agreementId} as staff. */
  public static ResponseEntity<String> post(
      TestRestTemplate rest, ApplicationContext context, Object agreementId) {
    return rest.exchange(
        "/api/signing/" + agreementId + "/request",
        HttpMethod.POST,
        new HttpEntity<>(staffHeaders(context)),
        String.class);
  }

  /** Headers carrying a fresh STAFF session cookie. */
  public static HttpHeaders staffHeaders(ApplicationContext context) {
    String token =
        StaffSessions.staffSession(
            context.getBean(IdentityService.class),
            context.getBean(HandoffService.class),
            context.getBean(SessionService.class),
            context.getBean(JdbcTemplate.class),
            STAFF_SUBJECT);
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(token));
    return headers;
  }
}
