package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins the CR-5 default-deny security posture against a <em>real</em> Tomcat servlet container over
 * a real port, driven by {@link TestRestTemplate}.
 *
 * <p>This deliberately does NOT use MockMvc: MockMvc rethrows a handler exception instead of
 * performing Boot's internal {@code /error} dispatch, so it cannot observe that a permitted
 * endpoint which errors would have its {@code /error} dispatch re-authorized against {@code
 * denyAll} and masked as 403. That exact bug shipped past a MockMvc test and was only caught here —
 * so the faithful, full-pipeline test is the authoritative coverage. It runs against the
 * Testcontainers harness (Postgres + MinIO) and {@code disabledWithoutDocker = true} makes it skip
 * (not fail) without a Docker daemon, consistent with the rest of the integration suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SecurityBaselineIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;
  @Autowired private JdbcTemplate jdbc;

  @Autowired private List<SecurityFilterChain> filterChains;

  @Test
  void exactlyOneSecurityFilterChainIsRegistered() {
    assertThat(filterChains).hasSize(1);
  }

  @Test
  void unmappedPathIsDeniedWith403() {
    assertThat(rest.getForEntity("/api/anything", String.class).getStatusCode().value())
        .isEqualTo(403);
  }

  @Test
  void nonRequestSigningSubPathIsDeniedWith403() {
    // /api/signing/** is NOT a blanket rule — only the exact */request (STAFF) and */progress are.
    assertThat(rest.getForEntity("/api/signing/list", String.class).getStatusCode().value())
        .isEqualTo(403);
  }

  @Test
  void webhookIsPermittedAndReachesTheController() {
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/webhooks/esign", HttpMethod.POST, new HttpEntity<>("{}", json), String.class);
    // 401 = the filter permitted it and it reached the controller (the webhook is CSRF-exempt; the
    // harness interceptor's token is incidental here -- CsrfProtectionIntegrationTest proves the
    // exemption with no token at all), whose
    // body-MAC verifier rejected the unsigned `{}` payload. A 403 would mean the filter (or the
    // /error re-dispatch) blocked it — that regression is exactly what this asserts against.
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void anAnonymousSigningRequestIsRejected() {
    // STAFF-only (anonymous-surface-abuse-controls D7). 403 from the chain, before the controller:
    // a non-UUID id would otherwise reach MVC and answer 400.
    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/abc/request", null, String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void aCustomerSessionCannotRequestSigning() {
    String customer =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "baseline-signing-customer");
    assertThat(signingRequestAs(customer).getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void aStaffSigningRequestPassesTheChainAndReachesTheController() {
    String staff =
        StaffSessions.staffSession(
            identityService, handoffService, sessionService, jdbc, "baseline-signing-staff");
    // 400 = past security and into MVC, which rejected the non-UUID path var. A 403 would mean
    // security blocked it. (The harness interceptor supplies the CSRF token;
    // CsrfProtectionIntegrationTest proves the same STAFF request without one is refused.)
    assertThat(signingRequestAs(staff).getStatusCode().value()).isEqualTo(400);
  }

  private ResponseEntity<String> signingRequestAs(String session) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(session));
    return rest.exchange(
        "/api/signing/abc/request", HttpMethod.POST, new HttpEntity<>(headers), String.class);
  }

  @Test
  void staffStampIntakeChallengesAnAnonymousCallerWith401() {
    // The staff surface is the one place a 401 is right: it tells an operator whose session lapsed
    // to re-authenticate, instead of the blanket 403 every other route still returns. The harness
    // interceptor supplies a valid CSRF token; without one the CSRF 403 would precede this 401.
    HttpHeaders multipart = new HttpHeaders();
    multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(null, multipart), String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void unlistedStaffSubPathsRemainDeniedByDefault() {
    // Only the exact POST /api/staff/estamp is authorized; anything else under /api/staff/ falls
    // through to anyRequest().denyAll().
    assertThat(rest.getForEntity("/api/staff/anything", String.class).getStatusCode().value())
        .isIn(401, 403);
  }

  @Test
  void actuatorHealthIsReachableWithHardeningHeaders() {
    ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    // Health must not leak component detail (show-details: never).
    assertThat(resp.getBody()).contains("\"status\"").doesNotContain("\"components\"");
    // Spring Security's default hardening headers are applied once it is on the classpath.
    assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
  }

  @Test
  void nonHealthActuatorEndpointIsNotReachable() {
    // Unexposed (only `health` is included) and denyAll'd by the filter — never 200 with data.
    assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode().value())
        .isIn(401, 403, 404);
  }
}
