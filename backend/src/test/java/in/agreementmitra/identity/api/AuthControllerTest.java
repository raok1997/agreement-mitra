package in.agreementmitra.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityRole;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.api.AuthDtos.SessionExchangeRequest;
import in.agreementmitra.identity.api.AuthDtos.SessionResponse;
import in.agreementmitra.identity.oauth.GoogleLoginService;
import in.agreementmitra.identity.oauth.GoogleLoginService.HandoffIssued;
import in.agreementmitra.identity.oauth.GoogleLoginService.StartRedirect;
import in.agreementmitra.identity.oauth.InvalidLoginException;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.identity.session.SessionService.SessionIssued;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {

  private final SessionService sessionService = mock(SessionService.class);
  private final IdentityService identityService = mock(IdentityService.class);
  private static final String SPA = "http://localhost:5173/auth/callback";
  private static final AuthProperties PROPS =
      new AuthProperties(
          "pepper",
          Duration.ofHours(1),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          new AuthProperties.Google(
              "client", "secret", "http://x/cb", SPA, "iss", "auth", "token", "jwks"),
          null);

  private final GoogleLoginService googleLoginService = mock(GoogleLoginService.class);
  private final SessionCookies sessionCookies = mock(SessionCookies.class);
  private final AuthController controller =
      new AuthController(
          googleLoginService, sessionService, identityService, sessionCookies, PROPS);

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @BeforeEach
  void browserCarriesItsBinding() {
    when(sessionCookies.readLoginBinding(request)).thenReturn("nonce");
  }

  private static SessionIssued issued() {
    return new SessionIssued(
        "session-value",
        new IdentitySummary(UUID.randomUUID(), "Asha", "a@x.com", IdentityRole.CUSTOMER),
        Instant.now().plusSeconds(3600));
  }

  @Test
  void exchangeReturnsOnlyTheSummaryAndEstablishesTheCookie() {
    SessionIssued issued = issued();
    when(sessionService.exchange("h", "nonce")).thenReturn(issued);

    ResponseEntity<SessionResponse> result =
        controller.exchange(new SessionExchangeRequest("h"), request, response);

    assertThat(result.getBody()).isNotNull();
    assertThat(result.getBody().me().displayName()).isEqualTo("Asha");
    assertThat(Arrays.stream(SessionResponse.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly("me");
    assertThat(result.getBody().toString()).doesNotContain("session-value");
    verify(sessionCookies).establish(request, response, issued);
  }

  @Test
  void aFailingExchangeNeverEstablishesOrRevokes() {
    when(sessionService.exchange("bad", "nonce")).thenThrow(new InvalidLoginException("reused"));

    assertThatThrownBy(
            () -> controller.exchange(new SessionExchangeRequest("bad"), request, response))
        .isInstanceOf(InvalidLoginException.class);

    verify(sessionCookies, never()).establish(any(), any(), any());
    verify(sessionService, never()).revoke(anyString());
    // The browser's own login-binding cookie is left alone.
    verify(sessionCookies, never()).clearLoginBinding(any());
    verify(sessionCookies, never()).setLoginBinding(any(), any());
  }

  @Test
  void aSuccessfulExchangeClearsTheBindingAfterEstablishing() {
    SessionIssued issued = issued();
    when(sessionService.exchange("h", "nonce")).thenReturn(issued);

    controller.exchange(new SessionExchangeRequest("h"), request, response);

    InOrder order = inOrder(sessionCookies);
    order.verify(sessionCookies).establish(request, response, issued);
    order.verify(sessionCookies).clearLoginBinding(response);
  }

  @Test
  void startSetsTheLoginBindingCookie() {
    when(googleLoginService.start()).thenReturn(new StartRedirect("https://google/auth", "n1"));

    ResponseEntity<Void> result = controller.start(response);

    assertThat(result.getStatusCode().value()).isEqualTo(302);
    assertThat(result.getHeaders().getLocation()).hasToString("https://google/auth");
    verify(sessionCookies).setLoginBinding(response, "n1");
  }

  @Test
  void aSuccessfulCallbackRedirectsWithTheHandoff() {
    when(googleLoginService.handleCallback("c", "s", "nonce")).thenReturn(new HandoffIssued("h1"));

    ResponseEntity<Void> result = controller.callback("c", "s", request);

    assertThat(result.getStatusCode().value()).isEqualTo(302);
    assertThat(result.getHeaders().getLocation()).hasToString(SPA + "#handoff=h1");
  }

  @Test
  void aRefusedCallbackRedirectsToTheFixedErrorFragment() {
    when(googleLoginService.handleCallback("c", "s", "nonce"))
        .thenThrow(new InvalidLoginException("unbound"));

    ResponseEntity<Void> result = controller.callback("c", "s", request);

    assertThat(result.getStatusCode().value()).isEqualTo(302);
    // An explicit fragment, so the browser never keeps one the request URL carried.
    assertThat(result.getHeaders().getLocation()).hasToString(SPA + "#error");
    assertThat(result.getBody()).isNull();
  }

  @Test
  void anInfrastructureFailureAtTheCallbackIsNotTurnedIntoARedirect() {
    when(googleLoginService.handleCallback("c", "s", "nonce"))
        .thenThrow(new IllegalStateException("db down"));

    assertThatThrownBy(() -> controller.callback("c", "s", request))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aSuccessfulExchangeMintsBeforeEstablishing() {
    SessionIssued issued = issued();
    when(sessionService.exchange("h", "nonce")).thenReturn(issued);

    controller.exchange(new SessionExchangeRequest("h"), request, response);

    InOrder order = inOrder(sessionService, sessionCookies);
    order.verify(sessionService).exchange("h", "nonce");
    order.verify(sessionCookies).establish(request, response, issued);
  }

  @Test
  void logoutWithNoCookieStillClearsAndDoesNotRevoke() {
    when(sessionCookies.read(request)).thenReturn(Optional.empty());

    ResponseEntity<Void> result = controller.logout(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(204);
    verify(sessionCookies).clear(request, response);
    verify(sessionService, never()).revoke(anyString());
  }

  @Test
  void logoutWithACookieRevokesThenClears() {
    when(sessionCookies.read(request)).thenReturn(Optional.of("live"));

    ResponseEntity<Void> result = controller.logout(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(204);
    InOrder order = inOrder(sessionService, sessionCookies);
    order.verify(sessionService).revoke("live");
    order.verify(sessionCookies).clear(request, response);
  }

  @Test
  void aFailingRevokeStillExpiresTheCookieAndReports500() {
    when(sessionCookies.read(request)).thenReturn(Optional.of("live"));
    org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
        .when(sessionService)
        .revoke("live");

    ResponseEntity<Void> result = controller.logout(request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(500);
    verify(sessionCookies).clear(request, response);
  }
}
