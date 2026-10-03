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

import in.agreementmitra.identity.IdentityRole;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.api.AuthDtos.SessionExchangeRequest;
import in.agreementmitra.identity.api.AuthDtos.SessionResponse;
import in.agreementmitra.identity.oauth.GoogleLoginService;
import in.agreementmitra.identity.oauth.InvalidLoginException;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.identity.session.SessionService.SessionIssued;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthControllerTest {

  private final SessionService sessionService = mock(SessionService.class);
  private final IdentityService identityService = mock(IdentityService.class);
  private final SessionCookies sessionCookies = mock(SessionCookies.class);
  private final AuthController controller =
      new AuthController(
          mock(GoogleLoginService.class), sessionService, identityService, sessionCookies);

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private static SessionIssued issued() {
    return new SessionIssued(
        "session-value",
        new IdentitySummary(UUID.randomUUID(), "Asha", "a@x.com", IdentityRole.CUSTOMER),
        Instant.now().plusSeconds(3600));
  }

  @Test
  void exchangeReturnsOnlyTheSummaryAndEstablishesTheCookie() {
    SessionIssued issued = issued();
    when(sessionService.exchange("h")).thenReturn(issued);

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
    when(sessionService.exchange("bad")).thenThrow(new InvalidLoginException("reused"));

    assertThatThrownBy(
            () -> controller.exchange(new SessionExchangeRequest("bad"), request, response))
        .isInstanceOf(InvalidLoginException.class);

    verify(sessionCookies, never()).establish(any(), any(), any());
    verify(sessionService, never()).revoke(anyString());
  }

  @Test
  void aSuccessfulExchangeMintsBeforeEstablishing() {
    SessionIssued issued = issued();
    when(sessionService.exchange("h")).thenReturn(issued);

    controller.exchange(new SessionExchangeRequest("h"), request, response);

    InOrder order = inOrder(sessionService, sessionCookies);
    order.verify(sessionService).exchange("h");
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
