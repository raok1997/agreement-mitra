package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.RawClient;
import in.agreementmitra.support.SessionCookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code auth.cookie.secure=false} end to end (cookie-session-auth task 5.5): both cookies drop
 * {@code Secure} and the {@code __Host-} prefix, keep no {@code Domain}, and the session filter
 * reads {@code am_session}; the login-binding cookie is {@code am_login} and is read under that
 * name (login-browser-binding). One extra Spring context -- the only one this CR adds.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "auth.cookie.secure=false")
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class InsecureCookieModeIntegrationTest {

  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @LocalServerPort private int port;

  @Test
  void bothCookiesDropSecureAndThePrefixAndTheFilterReadsAmSession() {
    RestTemplate raw = RawClient.on(port);

    ResponseEntity<String> csrf = raw.getForEntity("/api/auth/csrf", String.class);
    assertThat(csrf.getStatusCode().value()).isEqualTo(204);
    assertThat(SessionCookie.setCookies(csrf.getHeaders(), "__Host-XSRF-TOKEN")).isEmpty();
    String xsrfCookie = SessionCookie.lastSetCookie(csrf.getHeaders(), "XSRF-TOKEN").orElseThrow();
    assertThat(xsrfCookie).doesNotContain("Secure").contains("Path=/");
    String token = SessionCookie.lastValue(csrf.getHeaders(), "XSRF-TOKEN").orElseThrow();

    UUID identityId =
        identityService.findOrCreate(
            "google", "insecure-mode", "insecure-mode@example.com", true, "T insecure");
    String nonce = new SecretTokens().newToken();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + token + "; am_login=" + nonce);
    headers.set("X-XSRF-TOKEN", token);
    ResponseEntity<String> exchanged =
        raw.exchange(
            "/api/auth/session/exchange",
            HttpMethod.POST,
            new HttpEntity<>(
                "{\"handoff\":\"" + handoffService.issue(identityId, nonce) + "\"}", headers),
            String.class);
    assertThat(exchanged.getStatusCode().value()).isEqualTo(200);
    assertThat(SessionCookie.lastSetCookie(exchanged.getHeaders(), "am_login").orElseThrow())
        .contains("Max-Age=0")
        .doesNotContain("Secure");
    assertThat(SessionCookie.setCookies(exchanged.getHeaders(), SessionCookie.NAME)).isEmpty();
    String sessionCookie =
        SessionCookie.lastSetCookie(exchanged.getHeaders(), "am_session").orElseThrow();
    assertThat(sessionCookie)
        .contains("HttpOnly")
        .doesNotContain("Secure")
        .doesNotContainIgnoringCase("Domain=")
        .contains("Path=/");
    String session = SessionCookie.lastValue(exchanged.getHeaders(), "am_session").orElseThrow();

    HttpHeaders asSession = new HttpHeaders();
    asSession.set(HttpHeaders.COOKIE, "am_session=" + session);
    ResponseEntity<String> me =
        raw.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(asSession), String.class);
    assertThat(me.getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void startSetsAmLoginWithoutSecure() {
    ResponseEntity<String> start =
        RawClient.on(port).getForEntity("/api/auth/google/start", String.class);

    assertThat(start.getStatusCode().value()).isEqualTo(302);
    assertThat(SessionCookie.setCookies(start.getHeaders(), SessionCookie.LOGIN_BINDING_NAME))
        .isEmpty();
    assertThat(SessionCookie.lastSetCookie(start.getHeaders(), "am_login").orElseThrow())
        .contains("HttpOnly", "SameSite=Lax", "Path=/")
        .doesNotContain("Secure")
        .doesNotContainIgnoringCase("Domain=");
  }
}
