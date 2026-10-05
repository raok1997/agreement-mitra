package in.agreementmitra.identity;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.agreementmitra.identity.support.TokenHasher;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.LogCapture;
import in.agreementmitra.support.SessionCookie;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Login handshake end-to-end with Google stubbed (task 5.7): a WireMock OIDC token endpoint + JWKS
 * stand in for Google, and a Nimbus-signed ID token is validated against that JWKS. The full flow
 * -- start -> callback (find-or-create + handoff) -> exchange -> the session cookie authenticates
 * {@code GET /api/auth/me} -> logout revokes -- runs against real Postgres (Testcontainers). No
 * live Google.
 *
 * <p>The login-binding cookie set at start is carried by hand to the callback and the exchange, as
 * the browser would (login-browser-binding). The attack cases replay an attacker's callback URL or
 * handoff from a "victim" carrying no binding or its own.
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

  @RegisterExtension final LogCapture logs = LogCapture.root("in.agreementmitra", Level.DEBUG);

  @Autowired private TestRestTemplate rest;
  @Autowired private TokenHasher hasher;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthProperties authProperties;
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
    // 1. start -> 302 to Google with a state we echo back on the callback, and the login-binding
    // cookie that ties the callback and the exchange to this browser.
    Started start = start();
    // The account chooser is always shown, so a shared browser's Google session is never reused
    // silently after our sign-out.
    assertThat(queryParam(start.response().getHeaders().getLocation(), "prompt"))
        .isEqualTo("select_account");
    String bindingCookie =
        SessionCookie.lastSetCookie(start.response().getHeaders(), SessionCookie.LOGIN_BINDING_NAME)
            .orElseThrow();
    assertThat(bindingCookie)
        .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/")
        .doesNotContainIgnoringCase("Domain=");
    long ttlSeconds = authProperties.loginStateTtl().plus(authProperties.handoffTtl()).toSeconds();
    assertThat(maxAge(bindingCookie)).isGreaterThanOrEqualTo(ttlSeconds);
    // Only the nonce's hash is stored on the login-state row.
    assertThat(stateRowBindingHash(start.state())).isEqualTo(hasher.hash(start.nonce()));

    // 2. callback -> 302 back to the SPA with a single-use handoff in the fragment (no
    // token/session).
    ResponseEntity<Void> callback = callback("full-login-code", start.state(), start.nonce());
    assertThat(callback.getStatusCode().value()).isEqualTo(302);
    URI spaTarget = callback.getHeaders().getLocation();
    assertThat(spaTarget).isNotNull();
    assertThat(spaTarget.toString()).startsWith(SPA_CALLBACK + "#handoff=");
    String handoff = handoffFrom(callback);
    assertThat(handoff).isNotBlank();

    // 3. exchange the handoff -> the session in an HttpOnly cookie + the identity summary only,
    // and the login-binding cookie is expired.
    ResponseEntity<String> exchangedResp =
        exchange(handoff, SessionCookie.loginBindingHeader(start.nonce()));
    assertThat(exchangedResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> exchanged = json(exchangedResp.getBody());
    assertThat(exchanged).doesNotContainKey("session");
    String session =
        SessionCookie.lastValue(exchangedResp.getHeaders(), SessionCookie.NAME).orElseThrow();
    assertThat(session).isNotBlank();
    @SuppressWarnings("unchecked")
    Map<String, Object> me = (Map<String, Object>) exchanged.get("me");
    assertThat(me.get("email")).isEqualTo("alice@gmail.com");
    assertThat(expiredLoginBinding(exchangedResp.getHeaders())).isTrue();

    // 4. the session cookie authenticates a subsequent /api/auth/me.
    ResponseEntity<Map<String, Object>> meResp =
        rest.exchange(
            "/api/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(cookies(SessionCookie.header(session))),
            new ParameterizedTypeReference<>() {});
    assertThat(meResp.getStatusCode().value()).isEqualTo(200);
    assertThat(meResp.getBody()).containsEntry("email", "alice@gmail.com");

    // 5. logout revokes -> 204, expires the login-binding cookie too, and the same value no longer
    // authenticates.
    ResponseEntity<Void> logout =
        rest.exchange(
            "/api/auth/logout",
            HttpMethod.POST,
            new HttpEntity<>(cookies(SessionCookie.header(session))),
            Void.class);
    assertThat(logout.getStatusCode().value()).isEqualTo(204);
    assertThat(expiredLoginBinding(logout.getHeaders())).isTrue();

    ResponseEntity<String> afterLogout =
        rest.exchange(
            "/api/auth/me",
            HttpMethod.GET,
            new HttpEntity<>(cookies(SessionCookie.header(session))),
            String.class);
    assertThat(afterLogout.getStatusCode().value()).isIn(401, 403);
  }

  @Test
  void aReusedHandoffMintsNoSecondSession() {
    Started start = start();
    String handoff = handoffFrom(callback("reuse-code", start.state(), start.nonce()));
    String binding = SessionCookie.loginBindingHeader(start.nonce());

    assertThat(exchange(handoff, binding).getStatusCode()).isEqualTo(HttpStatus.OK);
    // Second exchange of the same handoff, with the same binding, is refused (single-use) with a
    // fixed 401 -- reuse is the only reason left for the refusal.
    ResponseEntity<String> second = exchange(handoff, binding);
    assertThat(second.getStatusCode().value()).isEqualTo(401);
    assertThat(SessionCookie.setCookies(second.getHeaders(), SessionCookie.NAME)).isEmpty();
  }

  @Test
  void aSessionAndABindingSentAsSeparateCookieHeadersBothArrive() {
    // Positive control for the CSRF interceptor's Cookie merge: if it kept only the first Cookie
    // header, the binding below would be dropped and this exchange would be refused.
    Started first = start();
    String priorSession =
        SessionCookie.lastValue(
                exchange(
                        handoffFrom(callback("first-code", first.state(), first.nonce())),
                        SessionCookie.loginBindingHeader(first.nonce()))
                    .getHeaders(),
                SessionCookie.NAME)
            .orElseThrow();

    Started second = start();
    String handoff = handoffFrom(callback("second-code", second.state(), second.nonce()));
    ResponseEntity<String> resp =
        exchange(
            handoff,
            SessionCookie.header(priorSession),
            SessionCookie.loginBindingHeader(second.nonce()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void anAttackersUnfollowedCallbackUrlIsRefusedInTheVictimsBrowser() {
    Started attacker = start();
    Started victim = start();
    String code = "attacker-code-" + UUID.randomUUID();

    for (String victimBinding : new String[] {null, victim.nonce()}) {
      ResponseEntity<Void> refused = callback(code, attacker.state(), victimBinding);

      assertThat(refused.getStatusCode().value()).isEqualTo(302);
      assertThat(refused.getHeaders().getLocation()).hasToString(SPA_CALLBACK + "#error");
    }
    // No code was exchanged with Google and the attacker's state is still unconsumed.
    assertThat(
            WIREMOCK.findAll(
                postRequestedFor(urlPathEqualTo("/token")).withRequestBody(containing(code))))
        .isEmpty();
    assertThat(consumedAt("oauth_login_state", "state_hash", attacker.state())).isNull();

    // The refusals burned nothing: the attacker's own browser still completes its login.
    ResponseEntity<Void> own = callback(code, attacker.state(), attacker.nonce());
    assertThat(own.getHeaders().getLocation().toString()).startsWith(SPA_CALLBACK + "#handoff=");
  }

  @Test
  void anUnknownStateRedirectsToTheErrorRoute() {
    Started start = start();

    ResponseEntity<Void> refused = callback("any", "not-a-state", start.nonce());

    assertThat(refused.getStatusCode().value()).isEqualTo(302);
    assertThat(refused.getHeaders().getLocation()).hasToString(SPA_CALLBACK + "#error");
  }

  @Test
  void anAttackersHandoffIsRefusedInTheVictimsBrowser() {
    Started attacker = start();
    String handoff = handoffFrom(callback("handoff-code", attacker.state(), attacker.nonce()));
    Started victim = start();

    for (String victimCookie :
        new String[] {null, SessionCookie.loginBindingHeader(victim.nonce())}) {
      ResponseEntity<String> refused =
          victimCookie == null ? exchange(handoff) : exchange(handoff, victimCookie);

      assertThat(refused.getStatusCode().value()).isEqualTo(401);
      assertThat(refused.getBody()).contains("urn:agreementmitra:problem:login-failed");
      assertThat(SessionCookie.setCookies(refused.getHeaders(), SessionCookie.NAME)).isEmpty();
      // The victim's own login-binding cookie is left alone.
      assertThat(SessionCookie.setCookies(refused.getHeaders(), SessionCookie.LOGIN_BINDING_NAME))
          .isEmpty();
    }
    assertThat(consumedAt("login_handoff", "handoff_hash", handoff)).isNull();

    // Still usable by the attacker's own browser, so the refusal was the binding and nothing else.
    assertThat(
            exchange(handoff, SessionCookie.loginBindingHeader(attacker.nonce())).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void secureModeIgnoresTheRightNonceUnderTheUnprefixedName() {
    Started start = start();
    String handoff = handoffFrom(callback("unprefixed-code", start.state(), start.nonce()));

    ResponseEntity<String> refused =
        exchange(handoff, SessionCookie.INSECURE_LOGIN_BINDING_NAME + "=" + start.nonce());

    assertThat(refused.getStatusCode().value()).isEqualTo(401);
    assertThat(consumedAt("login_handoff", "handoff_hash", handoff)).isNull();
  }

  @Test
  void theNonceAndOtherSecretsAppearInNoLogLineBodyOrRedirect() {
    String code = "log-scan-code-" + UUID.randomUUID();
    List<String> responseText = new ArrayList<>();

    Started start = start();
    responseText.add(String.valueOf(start.response().getHeaders().getLocation()));
    ResponseEntity<Void> callback = callback(code, start.state(), start.nonce());
    responseText.add(String.valueOf(callback.getHeaders().getLocation()));
    String handoff = handoffFrom(callback);
    ResponseEntity<String> exchanged =
        exchange(handoff, SessionCookie.loginBindingHeader(start.nonce()));
    assertThat(exchanged.getStatusCode()).isEqualTo(HttpStatus.OK);
    responseText.add(exchanged.getBody());
    String session =
        SessionCookie.lastValue(exchanged.getHeaders(), SessionCookie.NAME).orElseThrow();

    // Set-Cookie is excluded by design: the start response's cookie is the nonce's one carrier.
    assertThat(responseText).noneMatch(text -> text != null && text.contains(start.nonce()));

    // Our own loggers only; Spring/Hibernate are out of this scan's scope.
    List<String> ourLines =
        logs.events().stream()
            .filter(e -> e.getLoggerName().startsWith("in.agreementmitra"))
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    assertThat(ourLines).isNotEmpty();
    for (String secret :
        List.of(start.nonce(), start.state(), code, handoff, session, "alice@gmail.com")) {
      assertThat(ourLines).noneMatch(line -> line.contains(secret));
    }
  }

  // --- helpers ---------------------------------------------------------------

  /** A started login: the state echoed by Google, and the raw nonce from the binding cookie. */
  private record Started(String state, String nonce, ResponseEntity<Void> response) {}

  private Started start() {
    ResponseEntity<Void> start =
        noFollow()
            .exchange(url("/api/auth/google/start"), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    assertThat(start.getStatusCode().value()).isEqualTo(302);
    String state = queryParam(start.getHeaders().getLocation(), "state");
    assertThat(state).isNotBlank();
    String nonce =
        SessionCookie.lastValue(start.getHeaders(), SessionCookie.LOGIN_BINDING_NAME).orElseThrow();
    assertThat(nonce).isNotBlank();
    return new Started(state, nonce, start);
  }

  /** Follow Google's redirect to the callback, carrying {@code bindingNonce} if non-null. */
  private ResponseEntity<Void> callback(String code, String state, String bindingNonce) {
    HttpHeaders headers = new HttpHeaders();
    if (bindingNonce != null) {
      headers.add(HttpHeaders.COOKIE, SessionCookie.loginBindingHeader(bindingNonce));
    }
    return noFollow()
        .exchange(
            url("/api/auth/google/callback?code=" + code + "&state=" + state),
            HttpMethod.GET,
            new HttpEntity<>(headers),
            Void.class);
  }

  private static String handoffFrom(ResponseEntity<Void> callback) {
    String fragment = callback.getHeaders().getLocation().getFragment();
    assertThat(fragment).startsWith("handoff=");
    return fragment.substring("handoff=".length());
  }

  /** Exchange a handoff, sending each given cookie as its own Cookie header. */
  private ResponseEntity<String> exchange(String handoff, String... cookieHeaders) {
    HttpHeaders headers = cookies(cookieHeaders);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/api/auth/session/exchange",
        HttpMethod.POST,
        new HttpEntity<>("{\"handoff\":\"" + handoff + "\"}", headers),
        String.class);
  }

  private Map<String, Object> json(String body) {
    try {
      return objectMapper.readValue(body, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("response is not JSON", e);
    }
  }

  private static HttpHeaders cookies(String... cookieHeaders) {
    HttpHeaders headers = new HttpHeaders();
    for (String cookie : cookieHeaders) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    return headers;
  }

  private static boolean expiredLoginBinding(HttpHeaders headers) {
    return SessionCookie.lastSetCookie(headers, SessionCookie.LOGIN_BINDING_NAME)
        .map(cookie -> maxAge(cookie) == 0)
        .orElse(false);
  }

  private static long maxAge(String setCookie) {
    for (String part : setCookie.split(";")) {
      String trimmed = part.trim();
      if (trimmed.regionMatches(true, 0, "Max-Age=", 0, "Max-Age=".length())) {
        return Long.parseLong(trimmed.substring("Max-Age=".length()));
      }
    }
    throw new AssertionError("no Max-Age in " + setCookie.split("=")[0]);
  }

  private String stateRowBindingHash(String state) {
    return jdbc.queryForObject(
        "SELECT browser_binding_hash FROM oauth_login_state WHERE state_hash = ?",
        String.class,
        hasher.hash(state));
  }

  /** {@code consumed_at} of the row whose hash column matches {@code rawValue}'s hash. */
  private Timestamp consumedAt(String table, String hashColumn, String rawValue) {
    return jdbc.queryForObject(
        "SELECT consumed_at FROM " + table + " WHERE " + hashColumn + " = ?",
        Timestamp.class,
        hasher.hash(rawValue));
  }

  private static String queryParam(URI uri, String name) {
    return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
  }
}
