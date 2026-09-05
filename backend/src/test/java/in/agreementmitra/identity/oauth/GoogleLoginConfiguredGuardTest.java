package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.identity.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Login is optional and must fail closed at request time when Google is not configured (rather than
 * blocking application startup). With a blank client id/secret, {@code start()} and {@code
 * handleCallback()} refuse before touching any collaborator -- so the app can boot and serve
 * anonymous drafting while login stays unavailable, and it never falls back to a real project.
 */
class GoogleLoginConfiguredGuardTest {

  private static AuthProperties blankCreds() {
    return new AuthProperties(
        "pepper",
        Duration.ofHours(1),
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        new AuthProperties.Google("", "", "redirect", "spa", "issuer", "auth", "token", "jwks"));
  }

  // Collaborators are unused: the not-configured guard runs first and throws before any of them is
  // touched, so null is safe here and keeps the test focused on the guard.
  private final GoogleLoginService service =
      new GoogleLoginService(null, null, null, null, null, null, null, blankCreds());

  @Test
  void startFailsClosedWhenNotConfigured() {
    assertThatThrownBy(service::start).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void callbackFailsClosedWhenNotConfigured() {
    assertThatThrownBy(() -> service.handleCallback("code", "state"))
        .isInstanceOf(InvalidLoginException.class);
  }
}
