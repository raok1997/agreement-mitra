package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
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
    // /api/signing/** is NOT a blanket permit — only the exact */request stub is open.
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
    // 401 = the filter permitted it (no CSRF token needed) and it reached the controller, whose
    // body-MAC verifier rejected the unsigned `{}` payload. A 403 would mean the filter (or the
    // /error re-dispatch) blocked it — that regression is exactly what this asserts against.
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void signingRequestStubIsPermittedAndReachesTheController() {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/signing/abc/request", null, String.class);
    // 400 = permitted past security and reached MVC, which rejected the non-UUID path var. A 403
    // would mean security blocked it before dispatch — the regression this guards against.
    assertThat(resp.getStatusCode().value()).isEqualTo(400);
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
