package in.agreementmitra.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityRole;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.identity.session.SessionService.SessionIssued;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class SessionCookiesTest {

  private final SessionService sessionService = mock(SessionService.class);

  private static AuthProperties props(boolean secure) {
    return new AuthProperties(
        "pepper",
        Duration.ofHours(1),
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        new AuthProperties.Google(
            "client", "secret", "http://x/cb", "http://x/spa", "iss", "auth", "token", "jwks"),
        new AuthProperties.Cookie(secure));
  }

  private static SessionIssued issued(String value, Instant expiresAt) {
    return new SessionIssued(
        value,
        new IdentitySummary(UUID.randomUUID(), "Asha", "a@x.com", IdentityRole.CUSTOMER),
        expiresAt);
  }

  private CsrfTokenRepository repo(boolean secure) {
    return new AuthWebConfig().csrfTokenRepository(props(secure));
  }

  private SessionCookies cookies(boolean secure, CsrfTokenRepository repository) {
    return new SessionCookies(props(secure), sessionService, repository);
  }

  private static List<String> setCookies(MockHttpServletResponse response, String name) {
    return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
        .filter(h -> h.startsWith(name + "="))
        .toList();
  }

  @Test
  void establishInSecureModeSetsTheHardenedCookieWithMaxAgeFromExpiry() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true))
        .establish(request, response, issued("v1", Instant.now().plus(Duration.ofHours(2))));

    List<String> session = setCookies(response, "__Host-am_session");
    assertThat(session).hasSize(1);
    String header = session.get(0);
    assertThat(header)
        .startsWith("__Host-am_session=v1;")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .doesNotContain("Domain");
    long maxAge = Long.parseLong(header.replaceAll(".*Max-Age=(\\d+).*", "$1"));
    assertThat(maxAge).isBetween(7190L, 7200L);
  }

  @Test
  void establishInInsecureModeDropsSecureAndThePrefix() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(false, repo(false))
        .establish(
            new MockHttpServletRequest(),
            response,
            issued("v1", Instant.now().plus(Duration.ofHours(1))));

    assertThat(setCookies(response, "__Host-am_session")).isEmpty();
    List<String> session = setCookies(response, "am_session");
    assertThat(session).hasSize(1);
    assertThat(session.get(0)).contains("HttpOnly").doesNotContain("Secure").contains("Path=/");
    assertCsrfAttributes(response.getCookie("XSRF-TOKEN"), false);
  }

  @Test
  void establishFloorsMaxAgeAtZeroForAPastExpiry() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true))
        .establish(
            new MockHttpServletRequest(), response, issued("v1", Instant.now().minusSeconds(30)));

    assertThat(setCookies(response, "__Host-am_session").get(0)).contains("Max-Age=0");
  }

  @Test
  void establishRotatesCsrfWithAFreshToken() {
    CsrfTokenRepository repository = mock(CsrfTokenRepository.class);
    CsrfToken fresh = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "fresh");
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(repository.generateToken(request)).thenReturn(fresh);

    cookies(true, repository)
        .establish(request, response, issued("v1", Instant.now().plusSeconds(60)));

    InOrder order = inOrder(repository);
    order.verify(repository).generateToken(request);
    order.verify(repository).saveToken(fresh, request, response);
    verify(repository, never()).saveToken(isNull(), any(), any());
  }

  @Test
  void establishRotatesCsrfOnTheRealRepository() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-XSRF-TOKEN", "planted"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true))
        .establish(request, response, issued("v1", Instant.now().plusSeconds(60)));

    assertThat(setCookies(response, "__Host-XSRF-TOKEN")).hasSize(1);
    Cookie csrf = response.getCookie("__Host-XSRF-TOKEN");
    assertThat(csrf.getValue()).isNotBlank().isNotEqualTo("planted");
    assertCsrfAttributes(csrf, true);
  }

  @Test
  void establishRevokesADifferentPriorSessionLast() {
    CsrfTokenRepository repository = mock(CsrfTokenRepository.class);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-am_session", "prior"));

    cookies(true, repository)
        .establish(
            request, new MockHttpServletResponse(), issued("v1", Instant.now().plusSeconds(60)));

    InOrder order = inOrder(repository, sessionService);
    order.verify(repository).saveToken(any(), any(), any());
    order.verify(sessionService).revoke("prior");
  }

  @Test
  void aFailingPriorRevokeDoesNotCostTheNewLogin() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-am_session", "prior"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
        .when(sessionService)
        .revoke("prior");

    cookies(true, repo(true))
        .establish(request, response, issued("v1", Instant.now().plusSeconds(60)));

    assertThat(setCookies(response, "__Host-am_session")).hasSize(1);
    assertThat(setCookies(response, "__Host-am_session").get(0))
        .startsWith("__Host-am_session=v1;");
  }

  @Test
  void establishDoesNotRevokeWithoutAPriorCookie() {
    cookies(true, repo(true))
        .establish(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            issued("v1", Instant.now().plusSeconds(60)));

    verify(sessionService, never()).revoke(anyString());
  }

  @Test
  void clearExpiresTheCookieWithTheSameAttributesAndRotatesCsrf() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("__Host-am_session", "live"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true)).clear(request, response);

    String header = setCookies(response, "__Host-am_session").get(0);
    assertThat(header)
        .startsWith("__Host-am_session=;")
        .contains("Max-Age=0")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/");
    assertCsrfAttributes(response.getCookie("__Host-XSRF-TOKEN"), true);
    verify(sessionService, never()).revoke(anyString());
  }

  @Test
  void readReturnsOnlyTheConfiguredNameAndTreatsBlankAsAbsent() {
    SessionCookies secure = cookies(true, repo(true));

    MockHttpServletRequest other = new MockHttpServletRequest();
    other.setCookies(new Cookie("am_session", "x"), new Cookie("session", "y"));
    assertThat(secure.read(other)).isEmpty();

    MockHttpServletRequest blank = new MockHttpServletRequest();
    blank.setCookies(new Cookie("__Host-am_session", " "));
    assertThat(secure.read(blank)).isEmpty();

    assertThat(secure.read(new MockHttpServletRequest())).isEmpty();

    MockHttpServletRequest present = new MockHttpServletRequest();
    present.setCookies(new Cookie("__Host-am_session", "v"));
    assertThat(secure.read(present)).contains("v");

    MockHttpServletRequest insecure = new MockHttpServletRequest();
    insecure.setCookies(new Cookie("am_session", "w"));
    assertThat(cookies(false, repo(false)).read(insecure)).contains("w");
  }

  @Test
  void theRepositoryBeanIsNamedAndAttributedPerMode() {
    MockHttpServletResponse secureResponse = new MockHttpServletResponse();
    CsrfTokenRepository secure = repo(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    secure.saveToken(secure.generateToken(request), request, secureResponse);
    assertThat(secure.generateToken(request).getHeaderName()).isEqualTo("X-XSRF-TOKEN");
    assertCsrfAttributes(secureResponse.getCookie("__Host-XSRF-TOKEN"), true);

    MockHttpServletResponse insecureResponse = new MockHttpServletResponse();
    CsrfTokenRepository insecure = repo(false);
    insecure.saveToken(insecure.generateToken(request), request, insecureResponse);
    assertThat(insecureResponse.getCookie("__Host-XSRF-TOKEN")).isNull();
    assertCsrfAttributes(insecureResponse.getCookie("XSRF-TOKEN"), false);
  }

  @Test
  void theLoginBindingCookieIsHardenedInSecureMode() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true)).setLoginBinding(response, "n1");

    List<String> binding = setCookies(response, "__Host-am_login");
    assertThat(binding).hasSize(1);
    // loginStateTtl (5 min) + handoffTtl (60 s) + the 60 s margin.
    assertThat(binding.get(0))
        .startsWith("__Host-am_login=n1;")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .contains("Max-Age=420")
        .doesNotContain("Domain");
  }

  @Test
  void theLoginBindingCookieDropsSecureAndThePrefixInInsecureMode() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(false, repo(false)).setLoginBinding(response, "n1");

    assertThat(setCookies(response, "__Host-am_login")).isEmpty();
    List<String> binding = setCookies(response, "am_login");
    assertThat(binding).hasSize(1);
    assertThat(binding.get(0))
        .startsWith("am_login=n1;")
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/")
        .contains("Max-Age=420")
        .doesNotContain("Secure")
        .doesNotContain("Domain");
  }

  @Test
  void clearingTheLoginBindingCarriesTheNamePathAndSecureItWasSetWith() {
    MockHttpServletResponse secure = new MockHttpServletResponse();
    cookies(true, repo(true)).clearLoginBinding(secure);
    assertThat(setCookies(secure, "__Host-am_login").get(0))
        .startsWith("__Host-am_login=;")
        .contains("Max-Age=0")
        .contains("Secure")
        .contains("Path=/");

    MockHttpServletResponse insecure = new MockHttpServletResponse();
    cookies(false, repo(false)).clearLoginBinding(insecure);
    assertThat(setCookies(insecure, "am_login").get(0))
        .startsWith("am_login=;")
        .contains("Max-Age=0")
        .contains("Path=/")
        .doesNotContain("Secure");
  }

  @Test
  void logoutClearAlsoExpiresTheLoginBinding() {
    MockHttpServletResponse response = new MockHttpServletResponse();

    cookies(true, repo(true)).clear(new MockHttpServletRequest(), response);

    assertThat(setCookies(response, "__Host-am_login").get(0))
        .contains("Max-Age=0")
        .contains("Secure")
        .contains("Path=/");
  }

  @Test
  void readLoginBindingReadsOnlyTheConfiguredName() {
    SessionCookies secure = cookies(true, repo(true));

    MockHttpServletRequest unprefixed = new MockHttpServletRequest();
    unprefixed.setCookies(new Cookie("am_login", "planted"));
    assertThat(secure.readLoginBinding(unprefixed)).isNull();

    MockHttpServletRequest blank = new MockHttpServletRequest();
    blank.setCookies(new Cookie("__Host-am_login", " "));
    assertThat(secure.readLoginBinding(blank)).isNull();

    MockHttpServletRequest present = new MockHttpServletRequest();
    present.setCookies(new Cookie("__Host-am_login", "n1"));
    assertThat(secure.readLoginBinding(present)).isEqualTo("n1");

    MockHttpServletRequest insecure = new MockHttpServletRequest();
    insecure.setCookies(new Cookie("am_login", "n2"));
    assertThat(cookies(false, repo(false)).readLoginBinding(insecure)).isEqualTo("n2");
  }

  /** The repository writes a servlet Cookie; SameSite rides it as an attribute. */
  private static void assertCsrfAttributes(Cookie cookie, boolean secure) {
    assertThat(cookie).isNotNull();
    assertThat(cookie.getSecure()).isEqualTo(secure);
    assertThat(cookie.isHttpOnly()).isFalse();
    assertThat(cookie.getPath()).isEqualTo("/");
    assertThat(cookie.getDomain()).isNull();
    assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
  }
}
