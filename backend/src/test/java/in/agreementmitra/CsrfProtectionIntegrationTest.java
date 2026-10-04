package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.RawClient;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CSRF enforcement against the real chain (cookie-session-auth tasks 5.2 + 5.3). Uses {@link
 * RawClient} -- never the harness CSRF interceptor -- so the presence, absence, or mismatch of the
 * token is exactly what each test sends. Pins: refusal + problem type, header-only resolution,
 * non-reflection, planted-cookie recovery, eager issuance through the configured handler, the
 * webhook exemption and its exactness, and the CSRF-before-authorization precedence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CsrfProtectionIntegrationTest {

  private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";
  private static final String CSRF_TYPE = "urn:agreementmitra:problem:csrf";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;
  @LocalServerPort private int port;

  private RestTemplate raw;

  @BeforeEach
  void client() {
    raw = RawClient.on(port);
  }

  private static Map<String, Object> signer(String first, String email, String role) {
    return Map.of(
        "firstName",
        first,
        "lastName",
        "X",
        "fatherName",
        "Father " + first,
        "currentAddress",
        "Addr " + first,
        "email",
        email,
        "role",
        role);
  }

  private static Map<String, Object> validBody(String address) {
    return Map.of(
        "propertyAddress",
        address,
        "monthlyRent",
        new BigDecimal("25000.00"),
        "securityDeposit",
        new BigDecimal("50000.00"),
        "startDate",
        "2026-01-01",
        "endDate",
        "2026-12-01",
        "signers",
        List.of(
            signer("Asha", "asha@example.com", "OWNER"),
            signer("Tara", "tara@example.com", "TENANT")));
  }

  private static HttpHeaders json() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static HttpHeaders withCsrf(
      HttpHeaders headers, String cookieName, String cookie, String header) {
    if (cookie != null) {
      String existing = headers.getFirst(HttpHeaders.COOKIE);
      String pair = cookieName + "=" + cookie;
      headers.set(HttpHeaders.COOKIE, existing == null ? pair : existing + "; " + pair);
    }
    if (header != null) {
      headers.set(CSRF_HEADER, header);
    }
    return headers;
  }

  private Integer agreementsAt(String address) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM agreement WHERE property_address = ?", Integer.class, address);
  }

  private ResponseEntity<String> create(HttpHeaders headers, String address) {
    return raw.exchange(
        "/api/agreements",
        HttpMethod.POST,
        new HttpEntity<>(validBody(address), headers),
        String.class);
  }

  private static void assertCsrfRefusal(ResponseEntity<String> resp) {
    assertThat(resp.getStatusCode().value()).isEqualTo(403);
    assertThat(resp.getHeaders().getContentType()).isNotNull();
    assertThat(resp.getHeaders().getContentType().toString()).contains("problem+json");
    assertThat(resp.getBody()).contains(CSRF_TYPE);
  }

  // ---- 5.2 ----

  @Test
  void anUnsafeRequestWithoutATokenIsRefusedAndCreatesNothing() {
    ResponseEntity<String> resp = create(json(), "1 No Token Road");
    assertCsrfRefusal(resp);
    assertThat(agreementsAt("1 No Token Road")).isZero();
  }

  @Test
  void aMismatchedTokenIsRefused() {
    assertCsrfRefusal(create(withCsrf(json(), CSRF_COOKIE, "cookie-a", "header-b"), "2 Mismatch"));
    assertThat(agreementsAt("2 Mismatch")).isZero();
  }

  @Test
  void aTokenInARequestParameterOnlyIsRefused() {
    ResponseEntity<String> resp =
        raw.exchange(
            "/api/agreements?_csrf=param-token",
            HttpMethod.POST,
            new HttpEntity<>(
                validBody("3 Param Lane"), withCsrf(json(), CSRF_COOKIE, "param-token", null)),
            String.class);
    assertCsrfRefusal(resp);
  }

  @Test
  void aDistinctiveInvalidTokenIsNotReflected() {
    String distinctive = "REFLECT-ME-7c1d-csrf-probe";
    ResponseEntity<String> resp =
        create(withCsrf(json(), CSRF_COOKIE, "real-cookie", distinctive), "4 Reflect");
    assertCsrfRefusal(resp);
    assertThat(resp.getBody()).doesNotContain(distinctive).doesNotContain("real-cookie");
  }

  @Test
  void aPlantedNonPrefixedTokenRecoversOnRetry() {
    ResponseEntity<String> planted =
        create(withCsrf(json(), "XSRF-TOKEN", "planted", "planted"), "5 Planted");
    assertCsrfRefusal(planted);
    String fresh = SessionCookie.lastValue(planted.getHeaders(), CSRF_COOKIE).orElseThrow();
    assertThat(fresh).isNotBlank().isNotEqualTo("planted");

    ResponseEntity<String> retried =
        create(withCsrf(json(), CSRF_COOKIE, fresh, fresh), "5 Planted");
    assertThat(retried.getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void aMatchingCookieAndHeaderIsAccepted() {
    ResponseEntity<String> resp =
        create(withCsrf(json(), CSRF_COOKIE, "match", "match"), "6 Match");
    assertThat(resp.getStatusCode().value()).isEqualTo(201);
    assertThat(agreementsAt("6 Match")).isEqualTo(1);
  }

  @Test
  void theBootstrapEndpointIssuesAHardenedReadableToken() {
    ResponseEntity<String> resp = raw.getForEntity("/api/auth/csrf", String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(204);
    String cookie = SessionCookie.lastSetCookie(resp.getHeaders(), CSRF_COOKIE).orElseThrow();
    assertThat(cookie)
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .doesNotContain("HttpOnly")
        .doesNotContainIgnoringCase("Domain=");
  }

  @Test
  void aPermittedGetIssuesTheTokenEagerly() {
    ResponseEntity<String> resp = raw.getForEntity("/api/jurisdictions", String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(SessionCookie.lastSetCookie(resp.getHeaders(), CSRF_COOKIE)).isPresent();
  }

  @Test
  void noHttpSessionIsCreated() {
    List<ResponseEntity<String>> responses =
        List.of(
            raw.getForEntity("/api/auth/csrf", String.class),
            raw.getForEntity("/api/jurisdictions", String.class),
            create(json(), "7 Session"),
            create(withCsrf(json(), CSRF_COOKIE, "s", "s"), "7 Session"));
    for (ResponseEntity<String> resp : responses) {
      List<String> setCookies = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
      if (setCookies != null) {
        assertThat(setCookies).noneMatch(c -> c.startsWith("JSESSIONID"));
      }
    }
  }

  @Test
  void anAnonymousStaffCallWithoutATokenGetsTheCsrfRefusalAndWithATokenGets401() {
    HttpHeaders multipart = new HttpHeaders();
    multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> noToken =
        raw.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(null, multipart), String.class);
    assertCsrfRefusal(noToken);

    HttpHeaders withToken = new HttpHeaders();
    withToken.setContentType(MediaType.MULTIPART_FORM_DATA);
    withCsrf(withToken, CSRF_COOKIE, "t", "t");
    ResponseEntity<String> anon =
        raw.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(null, withToken), String.class);
    assertThat(anon.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void aCustomerOnAStaffEndpointWithATokenGetsABare403() {
    String customer =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "csrf-customer");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.set(HttpHeaders.COOKIE, SessionCookie.header(customer));
    withCsrf(headers, CSRF_COOKIE, "t", "t");

    ResponseEntity<String> resp =
        raw.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(null, headers), String.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(403);
    assertThat(resp.getBody() == null ? "" : resp.getBody()).doesNotContain(CSRF_TYPE);
  }

  @Test
  void theSigningRequestStubNeedsStaffAndATokenToPassTheChain() {
    // STAFF-only since anonymous-surface-abuse-controls D7, and still CSRF-protected.
    String staff =
        StaffSessions.staffSession(
            identityService, handoffService, sessionService, jdbc, "csrf-signing-staff");
    HttpHeaders withToken = new HttpHeaders();
    withToken.add(HttpHeaders.COOKIE, SessionCookie.header(staff));
    ResponseEntity<String> passed =
        raw.exchange(
            "/api/signing/abc/request",
            HttpMethod.POST,
            new HttpEntity<>(null, withCsrf(withToken, CSRF_COOKIE, "t", "t")),
            String.class);
    // 400 = past security and into MVC, which rejected the non-UUID path variable.
    assertThat(passed.getStatusCode().value()).isEqualTo(400);

    HttpHeaders noToken = new HttpHeaders();
    noToken.add(HttpHeaders.COOKIE, SessionCookie.header(staff));
    ResponseEntity<String> refused =
        raw.exchange(
            "/api/signing/abc/request",
            HttpMethod.POST,
            new HttpEntity<>(null, noToken),
            String.class);
    assertCsrfRefusal(refused);
  }

  // ---- 5.3 webhook exemption ----

  @Test
  void theEsignWebhookIsExemptAndReachesItsHmacCheck() {
    ResponseEntity<String> resp =
        raw.exchange(
            "/api/webhooks/esign", HttpMethod.POST, new HttpEntity<>("{}", json()), String.class);
    // 401 = the controller's body-MAC verifier rejected the unsigned payload; not a CSRF refusal.
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
    assertThat(resp.getBody() == null ? "" : resp.getBody()).doesNotContain(CSRF_TYPE);
  }

  @Test
  void theRazorpayWebhookIsExemptAndReachesItsSignatureCheck() {
    ResponseEntity<String> resp =
        raw.exchange(
            "/api/webhooks/razorpay",
            HttpMethod.POST,
            new HttpEntity<>("{}", json()),
            String.class);
    assertThat(resp.getStatusCode().value()).isNotEqualTo(403);
    assertThat(resp.getBody() == null ? "" : resp.getBody()).doesNotContain(CSRF_TYPE);
  }

  @Test
  void theExemptionIsExact() {
    assertCsrfRefusal(
        raw.exchange(
            "/api/agreements/" + UUID.randomUUID() + "/payment/callback",
            HttpMethod.POST,
            new HttpEntity<>("{}", json()),
            String.class));
    assertCsrfRefusal(
        raw.exchange(
            "/api/webhooks/esign/x",
            HttpMethod.POST,
            new HttpEntity<>("{}", json()),
            String.class));
  }
}
