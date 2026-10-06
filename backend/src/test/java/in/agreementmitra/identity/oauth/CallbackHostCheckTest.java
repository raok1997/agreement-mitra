package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.AuthProperties;
import org.junit.jupiter.api.Test;

/**
 * The host-only login-binding cookie needs the OAuth callback and the SPA callback on one host
 * (login-browser-binding D1). Ports do not matter -- cookies ignore them -- so the local {@code
 * localhost:8090} / {@code localhost:5173} split passes.
 */
class CallbackHostCheckTest {

  private static AuthProperties.Google google(String redirectUri, String spaCallbackUri) {
    return new AuthProperties.Google(
        "client", "secret", redirectUri, spaCallbackUri, "iss", "auth", "token", "jwks");
  }

  @Test
  void theSameHostOnDifferentPortsIsAccepted() {
    assertThat(
            OauthConfig.warnIfCallbackHostsDiffer(
                google(
                    "http://localhost:8090/api/auth/google/callback",
                    "http://localhost:5173/auth/callback")))
        .isFalse();
  }

  @Test
  void differentHostsAreWarnedAbout() {
    assertThat(
            OauthConfig.warnIfCallbackHostsDiffer(
                google(
                    "https://api.agreementmitra.com/api/auth/google/callback",
                    "https://www.agreementmitra.com/auth/callback")))
        .isTrue();
  }

  @Test
  void anUnparseableOrMissingUriIsLeftToTheOtherChecks() {
    assertThat(OauthConfig.warnIfCallbackHostsDiffer(google(null, "http://x/cb"))).isFalse();
    assertThat(OauthConfig.warnIfCallbackHostsDiffer(google("::bad uri", "http://x/cb"))).isFalse();
  }
}
