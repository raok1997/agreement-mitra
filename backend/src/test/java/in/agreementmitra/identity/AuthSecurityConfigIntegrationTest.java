package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SecurityConfig wiring for the auth routes (task 5.8), against the real full pipeline. The
 * handshake routes are reachable without a session; me/logout require one; and -- critically -- an
 * existing agreement route is still permitted exactly as before, proving no existing matcher
 * regressed.
 *
 * <p>Uses {@link TestRestTemplate} (which does not follow redirects by default) so a permitted
 * endpoint that 302s or errors is observed by its real status, not masked as 403. {@code
 * disabledWithoutDocker = true} skips without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AuthSecurityConfigIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @LocalServerPort private int port;

  /** A client that does NOT follow redirects, so a 302 is observed by its Location, not chased. */
  private static RestTemplate noFollow() {
    SimpleClientHttpRequestFactory factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String httpMethod)
              throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
          }
        };
    return new RestTemplate(factory);
  }

  @Test
  void meRequiresASession() {
    // No Bearer -> the filter is a no-op, the request stays unauthenticated -> denied (403, no
    // AuthenticationEntryPoint) rather than reaching the controller.
    assertThat(rest.getForEntity("/api/auth/me", String.class).getStatusCode().value())
        .isIn(401, 403);
  }

  @Test
  void logoutRequiresASession() {
    assertThat(rest.postForEntity("/api/auth/logout", null, String.class).getStatusCode().value())
        .isIn(401, 403);
  }

  @Test
  void anInvalidBearerDoesNotAuthenticateMe() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth("not-a-real-session-value");
    ResponseEntity<String> resp =
        rest.exchange("/api/auth/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    assertThat(resp.getStatusCode().value()).isIn(401, 403);
  }

  @Test
  void loginStartIsPermittedAndRedirectsToGoogle() {
    // Permitted (no session yet) and reaches the controller, which 302s to Google's authorization
    // endpoint. A 403 would mean the security chain blocked it.
    ResponseEntity<String> resp =
        noFollow()
            .getForEntity("http://localhost:" + port + "/api/auth/google/start", String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(302);
    assertThat(resp.getHeaders().getLocation()).isNotNull();
    assertThat(resp.getHeaders().getLocation().toString()).contains("accounts.google.com");
  }

  @Test
  void sessionExchangeIsPermittedAndReachesTheController() {
    // Permitted; reaches the controller, which rejects the unknown handoff as 401 (login failed).
    // A 403 would mean the chain blocked it before dispatch.
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> resp =
        rest.exchange(
            "/api/auth/session/exchange",
            HttpMethod.POST,
            new HttpEntity<>("{\"handoff\":\"nope\"}", json),
            String.class);
    assertThat(resp.getStatusCode().value()).isEqualTo(401);
  }

  @Test
  void existingAgreementRoutesAreNotRegressed() {
    // POST /api/agreements is still permitted: an empty body reaches MVC and fails validation
    // (400),
    // not blocked by security (403).
    HttpHeaders json = new HttpHeaders();
    json.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> create =
        rest.exchange(
            "/api/agreements", HttpMethod.POST, new HttpEntity<>("{}", json), String.class);
    assertThat(create.getStatusCode().value()).isEqualTo(400);

    // GET /api/agreements/{id} is still permitted: an unknown id reaches the controller and 404s.
    ResponseEntity<String> read =
        rest.getForEntity("/api/agreements/" + UUID.randomUUID(), String.class);
    assertThat(read.getStatusCode().value()).isEqualTo(404);
  }
}
