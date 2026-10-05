package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.RawClient;
import in.agreementmitra.support.SessionCookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The session cookie end to end (cookie-session-auth task 5.1), against the real chain and real
 * Postgres. Uses {@link RawClient} -- never the harness CSRF interceptor -- so every cookie and
 * header sent is exactly what the test set. Handoffs come from the real {@link HandoffService},
 * each bound to its own login-binding nonce, which every exchange presents unless a test says
 * otherwise -- so a refusal here is for the reason the test names, not for a missing binding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SessionCookieIntegrationTest {

  private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final String PRESENTED_CSRF = "presented-csrf-token";
  private static final String CSRF_TYPE = "urn:agreementmitra:problem:csrf";

  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private RestTemplate raw;

  @BeforeEach
  void client() {
    raw = RawClient.on(port);
  }

  /** A handoff and the raw login-binding nonce it is bound to. */
  private record Bound(String handoff, String nonce) {}

  private Bound handoffFor(String subject) {
    UUID identityId =
        identityService.findOrCreate(
            "google", subject, subject + "@example.com", true, "T " + subject);
    String nonce = new SecretTokens().newToken();
    return new Bound(handoffService.issue(identityId, nonce), nonce);
  }

  private static HttpHeaders headers(String sessionValue, boolean csrf) {
    return headers(sessionValue, null, csrf);
  }

  /**
   * Headers carrying the given cookies (one Cookie header) and, if csrf, the matching header. A
   * null session or binding is omitted.
   */
  private static HttpHeaders headers(String sessionValue, String bindingNonce, boolean csrf) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    List<String> cookies = new ArrayList<>();
    if (sessionValue != null) {
      cookies.add(SessionCookie.header(sessionValue));
    }
    if (bindingNonce != null) {
      cookies.add(SessionCookie.loginBindingHeader(bindingNonce));
    }
    if (csrf) {
      cookies.add(CSRF_COOKIE + "=" + PRESENTED_CSRF);
      headers.set(CSRF_HEADER, PRESENTED_CSRF);
    }
    if (!cookies.isEmpty()) {
      headers.set(HttpHeaders.COOKIE, String.join("; ", cookies));
    }
    return headers;
  }

  /** Exchange a bound handoff, presenting its own binding. */
  private ResponseEntity<String> exchange(Bound bound, String priorSession, boolean csrf) {
    return exchange(bound.handoff(), bound.nonce(), priorSession, csrf);
  }

  private ResponseEntity<String> exchange(
      String handoff, String bindingNonce, String priorSession, boolean csrf) {
    return raw.exchange(
        "/api/auth/session/exchange",
        HttpMethod.POST,
        new HttpEntity<>(
            "{\"handoff\":\"" + handoff + "\"}", headers(priorSession, bindingNonce, csrf)),
        String.class);
  }

  private String sessionFrom(ResponseEntity<?> response) {
    return SessionCookie.lastValue(response.getHeaders(), SessionCookie.NAME).orElseThrow();
  }

  private ResponseEntity<Map<String, Object>> me(String sessionValue) {
    return raw.exchange(
        "/api/auth/me",
        HttpMethod.GET,
        new HttpEntity<>(headers(sessionValue, false)),
        new ParameterizedTypeReference<>() {});
  }

  private ResponseEntity<String> logout(String sessionValue) {
    return raw.exchange(
        "/api/auth/logout",
        HttpMethod.POST,
        new HttpEntity<>(headers(sessionValue, true)),
        String.class);
  }

  @Test
  void exchangeWithCsrfSetsAHardenedCookieAndReturnsOnlyMe() {
    ResponseEntity<String> resp = exchange(handoffFor("cookie-ok"), null, true);

    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"me\"").doesNotContain("\"session\"");
    String cookie =
        SessionCookie.lastSetCookie(resp.getHeaders(), SessionCookie.NAME).orElseThrow();
    assertThat(cookie)
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .doesNotContainIgnoringCase("Domain=");
    long ttl = authProperties.sessionTtl().toSeconds();
    long maxAge = Long.parseLong(cookie.replaceAll(".*Max-Age=(\\d+).*", "$1"));
    assertThat(maxAge).isBetween(ttl - 60, ttl);
    assertThat(resp.getBody()).doesNotContain(sessionFrom(resp));

    String rotated = SessionCookie.lastValue(resp.getHeaders(), CSRF_COOKIE).orElseThrow();
    assertThat(rotated).isNotBlank().isNotEqualTo(PRESENTED_CSRF);
  }

  @Test
  void exchangeWithoutCsrfIsRefusedAndLeavesTheHandoffUnconsumed() {
    Bound handoff = handoffFor("cookie-nocsrf");

    ResponseEntity<String> refused = exchange(handoff, null, false);
    assertThat(refused.getStatusCode().value()).isEqualTo(403);
    assertThat(refused.getBody()).contains(CSRF_TYPE);
    assertThat(SessionCookie.setCookies(refused.getHeaders(), SessionCookie.NAME)).isEmpty();

    assertThat(exchange(handoff, null, true).getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void aReusedOrExpiredHandoffIsRefusedWithNoSessionCookie() {
    Bound handoff = handoffFor("cookie-reuse");
    assertThat(exchange(handoff, null, true).getStatusCode().value()).isEqualTo(200);

    ResponseEntity<String> reused = exchange(handoff, null, true);
    assertThat(reused.getStatusCode().value()).isEqualTo(401);
    assertThat(SessionCookie.setCookies(reused.getHeaders(), SessionCookie.NAME)).isEmpty();

    UUID expiredIdentity =
        identityService.findOrCreate(
            "google", "cookie-expired", "cookie-expired@example.com", true, "T expired");
    String expiredNonce = new SecretTokens().newToken();
    Bound expired = new Bound(handoffService.issue(expiredIdentity, expiredNonce), expiredNonce);
    jdbc.update(
        "UPDATE login_handoff SET expires_at = now() - interval '1 minute' WHERE identity_id = ?",
        expiredIdentity);
    ResponseEntity<String> stale = exchange(expired, null, true);
    assertThat(stale.getStatusCode().value()).isEqualTo(401);
    assertThat(SessionCookie.setCookies(stale.getHeaders(), SessionCookie.NAME)).isEmpty();
  }

  @Test
  void exchangingWhileSignedInRevokesThePriorSession() {
    String sessionA = sessionFrom(exchange(handoffFor("cookie-switch-a"), null, true));
    assertThat(me(sessionA).getStatusCode().value()).isEqualTo(200);

    ResponseEntity<String> toB = exchange(handoffFor("cookie-switch-b"), sessionA, true);
    assertThat(toB.getStatusCode().value()).isEqualTo(200);
    String sessionB = sessionFrom(toB);

    assertThat(me(sessionA).getStatusCode().value()).isIn(401, 403);
    ResponseEntity<Map<String, Object>> asB = me(sessionB);
    assertThat(asB.getStatusCode().value()).isEqualTo(200);
    assertThat(asB.getBody()).containsEntry("email", "cookie-switch-b@example.com");
  }

  @Test
  void aBadHandoffWhileSignedInLeavesThePriorSessionIntact() {
    String sessionA = sessionFrom(exchange(handoffFor("cookie-keep-a"), null, true));

    // A non-blank binding is present, so the unknown handoff is the only reason for the refusal.
    ResponseEntity<String> refused =
        exchange("garbage-handoff", new SecretTokens().newToken(), sessionA, true);
    assertThat(refused.getStatusCode().value()).isEqualTo(401);
    assertThat(SessionCookie.setCookies(refused.getHeaders(), SessionCookie.NAME)).isEmpty();

    assertThat(me(sessionA).getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void aHandoffBoundToAnotherBrowserLeavesThePriorSessionIntact() {
    String sessionA = sessionFrom(exchange(handoffFor("cookie-bound-a"), null, true));
    Bound other = handoffFor("cookie-bound-b");

    ResponseEntity<String> refused =
        exchange(other.handoff(), new SecretTokens().newToken(), sessionA, true);
    assertThat(refused.getStatusCode().value()).isEqualTo(401);
    assertThat(SessionCookie.setCookies(refused.getHeaders(), SessionCookie.NAME)).isEmpty();

    ResponseEntity<Map<String, Object>> stillA = me(sessionA);
    assertThat(stillA.getStatusCode().value()).isEqualTo(200);
    assertThat(stillA.getBody()).containsEntry("email", "cookie-bound-a@example.com");
  }

  @Test
  void theCookieAuthenticatesButBearerAndUnknownCookiesDoNot() {
    String session = sessionFrom(exchange(handoffFor("cookie-auth"), null, true));
    assertThat(me(session).getStatusCode().value()).isEqualTo(200);

    HttpHeaders bearer = new HttpHeaders();
    bearer.setBearerAuth(session);
    assertThat(
            raw.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearer), String.class)
                .getStatusCode()
                .value())
        .isIn(401, 403);

    assertThat(me("unknown-session-value").getStatusCode().value()).isIn(401, 403);

    String expiring = sessionFrom(exchange(handoffFor("cookie-expiring"), null, true));
    UUID expiringIdentity =
        identityService.findOrCreate(
            "google", "cookie-expiring", "cookie-expiring@example.com", true, "T cookie-expiring");
    jdbc.update(
        "UPDATE auth_session SET expires_at = now() - interval '1 minute' WHERE identity_id = ?",
        expiringIdentity);
    assertThat(me(expiring).getStatusCode().value()).isIn(401, 403);
  }

  @Test
  void logoutRevokesExpiresTheCookieAndRotatesCsrf() {
    String session = sessionFrom(exchange(handoffFor("cookie-logout"), null, true));

    ResponseEntity<String> out = logout(session);
    assertThat(out.getStatusCode().value()).isEqualTo(204);
    assertThat(SessionCookie.lastSetCookie(out.getHeaders(), SessionCookie.NAME).orElseThrow())
        .contains("Max-Age=0")
        .contains("HttpOnly")
        .contains("Path=/");
    assertThat(SessionCookie.lastValue(out.getHeaders(), CSRF_COOKIE).orElseThrow())
        .isNotEqualTo(PRESENTED_CSRF);
    assertThat(me(session).getStatusCode().value()).isIn(401, 403);
  }

  @Test
  void logoutWithAStaleCookieStillExpiresIt() {
    ResponseEntity<String> out = logout("already-gone");
    assertThat(out.getStatusCode().value()).isEqualTo(204);
    assertThat(SessionCookie.lastSetCookie(out.getHeaders(), SessionCookie.NAME).orElseThrow())
        .contains("Max-Age=0");
  }

  @Test
  void anonymousMeIsRejectedAndAnonymousLogoutWithCsrfIs204() {
    assertThat(me(null).getStatusCode().value()).isIn(401, 403);
    ResponseEntity<String> out = logout(null);
    assertThat(out.getStatusCode().value()).isEqualTo(204);
    List<String> cleared = SessionCookie.setCookies(out.getHeaders(), SessionCookie.NAME);
    assertThat(cleared).isNotEmpty();
  }
}
