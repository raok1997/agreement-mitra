package in.agreementmitra.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.identity.AuthProperties.Cookie;
import in.agreementmitra.identity.AuthProperties.Google;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

  private static AuthProperties props(Google google, Cookie cookie) {
    return new AuthProperties(
        "pepper",
        Duration.ofHours(1),
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        google,
        cookie);
  }

  private static Google google(String redirectUri, String spaCallbackUri) {
    return new Google(
        "client", "secret", redirectUri, spaCallbackUri, "issuer", "auth", "token", "jwks");
  }

  @Test
  void anAbsentCookieBlockMeansSecure() {
    AuthProperties props =
        props(google("http://localhost:8090/cb", "http://localhost:5173/cb"), null);
    assertThat(props.cookie().secure()).isTrue();
  }

  @Test
  void insecureWithHttpUrisIsAccepted() {
    AuthProperties props =
        props(google("http://localhost:8090/cb", "http://192.168.1.5:5173/cb"), new Cookie(false));
    assertThat(props.cookie().secure()).isFalse();
  }

  @Test
  void insecureWithAnHttpsSpaCallbackIsRefused() {
    assertThatThrownBy(
            () ->
                props(
                    google("http://localhost:8090/cb", "https://agreementmitra.in/auth/callback"),
                    new Cookie(false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_COOKIE_SECURE");
  }

  @Test
  void insecureWithAnUppercaseHttpsRedirectIsRefused() {
    assertThatThrownBy(
            () ->
                props(
                    google("  HTTPS://agreementmitra.in/api/auth/google/callback", "http://x/cb"),
                    new Cookie(false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_COOKIE_SECURE");
  }

  @Test
  void aMissingGoogleBlockDoesNotThrow() {
    assertThat(props(null, new Cookie(false)).cookie().secure()).isFalse();
  }

  @Test
  void insecureWithAnUnparseableUriIsRefused() {
    assertThatThrownBy(() -> props(google("http://ok/cb", "http://bad host/^"), new Cookie(false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_COOKIE_SECURE");
  }

  @Test
  void insecureWithASchemelessRelativeUriIsRefused() {
    assertThatThrownBy(() -> props(google("http://ok/cb", "/auth/callback"), new Cookie(false)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_COOKIE_SECURE");
  }

  @Test
  void secureModeIgnoresTheUris() {
    assertThat(props(google("/relative", "https://x/cb"), new Cookie(true)).cookie().secure())
        .isTrue();
  }
}
