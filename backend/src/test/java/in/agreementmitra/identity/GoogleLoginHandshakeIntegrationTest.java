package in.agreementmitra.identity;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.agreementmitra.support.HarnessTestConfig;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Login handshake end-to-end with Google stubbed (task 5.7): a WireMock OIDC token endpoint + JWKS
 * stand in for Google, and a Nimbus-signed ID token is validated against that JWKS. The full flow
 * -- start -> callback (find-or-create + handoff) -> exchange -> Bearer authenticates {@code GET
 * /api/auth/me} -> logout revokes -- runs against real Postgres (Testcontainers). No live Google.
 *
 * <p>{@link TestRestTemplate} does not follow redirects, so the {@code 302}s from start/callback
 * are observed by their {@code Location} (from which the {@code state} and {@code handoff} are
 * read).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class GoogleLoginHandshakeIntegrationTest {

  private static final String ISSUER = "https://accounts.google.com";
  private static final String CLIENT_ID = "handshake-test-client";
  private static final String SPA_CALLBACK = "http://localhost:5173/auth/callback";
  private static final String KEY_ID = "test-key";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
  private static final RSAKey RSA_KEY = generateKey();

  static {
    WIREMOCK.start();
  }

  private static RSAKey generateKey() {
    try {
      return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
    } catch (Exception e) {
      throw new IllegalStateException("could not generate test RSA key", e);
    }
  }

  @DynamicPropertySource
  static void googleProperties(DynamicPropertyRegistry registry) {
    registry.add("auth.google.client-id", () -> CLIENT_ID);
    registry.add("auth.google.client-secret", () -> "handshake-secret");
    registry.add("auth.google.issuer", () -> ISSUER);
    registry.add("auth.google.spa-callback-uri", () -> SPA_CALLBACK);
    registry.add("auth.google.redirect-uri", () -> "http://localhost/api/auth/google/callback");
    registry.add("auth.google.token-uri", () -> WIREMOCK.baseUrl() + "/token");
    registry.add("auth.google.jwks-uri", () -> WIREMOCK.baseUrl() + "/jwks");
    // Keep the authorization endpoint a stable, inspectable value for the start redirect.
    registry.add(
        "auth.google.authorization-uri", () -> "https://accounts.google.com/o/oauth2/v2/auth");
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

  @Autowired private TestRestTemplate rest;
  @LocalServerPort private int port;

  /**
   * A client that does NOT follow redirects, so the 302s from start/callback expose their Location.
   */
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

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @BeforeEach
  void stubGoogle() throws Exception {
    WIREMOCK.resetAll();
    WIREMOCK.stubFor(
        get(urlPathEqualTo("/jwks"))
            .willReturn(okJson(new JWKSet(RSA_KEY.toPublicJWK()).toString())));
    WIREMOCK.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                okJson(
                    "{\"access_token\":\"stub\",\"token_type\":\"Bearer\",\"id_token\":\""
                        + signedIdToken("google-subject-1", "alice@gmail.com", true)
                        + "\"}")));
  }

  private static String signedIdToken(String subject, String email, boolean emailVerified)
      throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .audience(CLIENT_ID)
            .subject(subject)
            .claim("email", email)
            .claim("email_verified", emailVerified)
            .claim("name", "Alice Example")
            .issueTime(Date.from(now.minusSeconds(30)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims);
    jwt.sign(new RSASSASigner(RSA_KEY));
    return jwt.serialize();
  }

  @Test
  void fullLoginHandshakeAuthenticatesThenLogoutRevokes() {
    // 1. start -> 302 to Google with a state we echo back on the callback.
    ResponseEntity<Void> start =
        noFollow()
            .exchange(url("/api/auth/google/start"), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    assertThat(start.getStatusCode().value()).isEqualTo(302);
    String state = queryParam(start.getHeaders().getLocation(), "state");
    assertThat(state).isNotBlank();

    // 2. callback -> 302 back to the SPA with a single-use handoff in the fragment (no
    // token/session).
    ResponseEntity<Void> callback =
        noFollow()
            .exchange(
                url("/api/auth/google/callback?code=any-code&state=" + state),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Void.class);
    assertThat(callback.getStatusCode().value()).isEqualTo(302);
    URI spaTarget = callback.getHeaders().getLocation();
    assertThat(spaTarget).isNotNull();
    assertThat(spaTarget.toString()).startsWith(SPA_CALLBACK + "#handoff=");
    String handoff = spaTarget.getFragment().substring("handoff=".length());
    assertThat(handoff).isNotBlank();

    // 3. exchange the handoff -> a session value (once) + the identity summary.
    Map<String, Object> exchanged = exchange(handoff, org.springframework.http.HttpStatus.OK);
    String session = (String) exchanged.get("session");
    assertThat(session).isNotBlank();
    @SuppressWarnings("unchecked")
    Map<String, Object> me = (Map<String, Object>) exchanged.get("me");
    assertThat(me.get("email")).isEqualTo("alice@gmail.com");

    // 4. the Bearer session authenticates a subsequent /api/auth/me.
    ResponseEntity<Map<String, Object>> meResp =
        rest.exchange(
            "/api/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(bearer(session)),
            new ParameterizedTypeReference<>() {});
    assertThat(meResp.getStatusCode().value()).isEqualTo(200);
    assertThat(meResp.getBody()).containsEntry("email", "alice@gmail.com");

    // 5. logout revokes -> 204, and the same value no longer authenticates.
    ResponseEntity<Void> logout =
        rest.exchange(
            "/api/auth/logout", HttpMethod.POST, new HttpEntity<>(bearer(session)), Void.class);
    assertThat(logout.getStatusCode().value()).isEqualTo(204);

    ResponseEntity<String> afterLogout =
        rest.exchange(
            "/api/auth/me", HttpMethod.GET, new HttpEntity<>(bearer(session)), String.class);
    assertThat(afterLogout.getStatusCode().value()).isIn(401, 403);
  }

  @Test
  void aReusedHandoffMintsNoSecondSession() {
    ResponseEntity<Void> start =
        noFollow()
            .exchange(url("/api/auth/google/start"), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    String state = queryParam(start.getHeaders().getLocation(), "state");
    ResponseEntity<Void> callback =
        noFollow()
            .exchange(
                url("/api/auth/google/callback?code=any&state=" + state),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                Void.class);
    String handoff =
        callback.getHeaders().getLocation().getFragment().substring("handoff=".length());

    exchange(handoff, org.springframework.http.HttpStatus.OK); // first exchange succeeds
    // Second exchange of the same handoff is refused (single-use) with a fixed 401.
    ResponseEntity<String> second =
        rest.exchange(
            "/api/auth/session/exchange",
            HttpMethod.POST,
            new HttpEntity<>("{\"handoff\":\"" + handoff + "\"}", jsonHeaders()),
            String.class);
    assertThat(second.getStatusCode().value()).isEqualTo(401);
  }

  // --- helpers ---------------------------------------------------------------

  private Map<String, Object> exchange(
      String handoff, org.springframework.http.HttpStatus expected) {
    ResponseEntity<Map<String, Object>> resp =
        rest.exchange(
            "/api/auth/session/exchange",
            HttpMethod.POST,
            new HttpEntity<>("{\"handoff\":\"" + handoff + "\"}", jsonHeaders()),
            new ParameterizedTypeReference<>() {});
    assertThat(resp.getStatusCode()).isEqualTo(expected);
    return resp.getBody();
  }

  private static HttpHeaders jsonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private static HttpHeaders bearer(String session) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(session);
    return headers;
  }

  private static String queryParam(URI uri, String name) {
    return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
  }
}
