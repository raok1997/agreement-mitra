package in.agreementmitra.identity;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity/login configuration, bound from {@code auth.*}. Secrets ({@code google.clientSecret},
 * {@code hashPepper}) come from env vars only and are never committed; a missing Google client
 * secret fails fast at startup (see {@link OauthConfig}) rather than falling back to a real
 * project.
 *
 * <p>Java-{@code public} only so the module's internal sub-packages ({@code oauth}, {@code
 * session}, {@code support}) can inject it; it is the identity module's own config type, not a
 * semantic part of its public API. Endpoint URIs are overridable so integration tests can point the
 * handshake at a stubbed Google (a mock token endpoint + JWKS) with no network.
 *
 * <p>{@code cookie.secure} (env {@code AUTH_COOKIE_SECURE}, default {@code true}) controls the
 * browser-session cookies (cookie-session-auth D5). {@code false} drops {@code Secure} and the
 * {@code __Host-} prefix for plain-http LAN/Safari testing. Startup refuses {@code false} when
 * either Google URI is {@code https} or cannot be parsed to a scheme, so a deployed site cannot run
 * with an insecure cookie.
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    String hashPepper,
    Duration sessionTtl,
    Duration handoffTtl,
    Duration loginStateTtl,
    Google google,
    Cookie cookie) {

  public AuthProperties {
    if (cookie == null) {
      cookie = new Cookie(true);
    }
    if (!cookie.secure() && google != null) {
      refuseInsecureCookieFor(google.spaCallbackUri());
      refuseInsecureCookieFor(google.redirectUri());
    }
  }

  private static void refuseInsecureCookieFor(String uri) {
    if (uri == null) {
      return;
    }
    String scheme;
    try {
      scheme = new URI(uri.trim()).getScheme();
    } catch (URISyntaxException e) {
      throw insecureCookieRefused("a Google URI cannot be parsed");
    }
    if (scheme == null || scheme.isBlank()) {
      throw insecureCookieRefused("a Google URI has no scheme");
    }
    if ("https".equals(scheme.toLowerCase(Locale.ROOT))) {
      throw insecureCookieRefused("a Google URI is https");
    }
  }

  private static IllegalStateException insecureCookieRefused(String reason) {
    return new IllegalStateException(
        "AUTH_COOKIE_SECURE=false is refused because "
            + reason
            + ": an https (or unverifiable) deployment must keep secure session cookies");
  }

  /**
   * Google OAuth (OIDC) client + endpoint configuration. {@code clientId}/{@code clientSecret} are
   * the sandbox OAuth client credentials (secret env-sourced). {@code redirectUri} is our callback
   * Google returns to; {@code spaCallbackUri} is where we 302 the browser after minting the
   * handoff. {@code issuer}/{@code authorizationUri}/{@code tokenUri}/{@code jwksUri} default to
   * Google's real endpoints and are overridden in tests.
   */
  public record Google(
      String clientId,
      String clientSecret,
      String redirectUri,
      String spaCallbackUri,
      String issuer,
      String authorizationUri,
      String tokenUri,
      String jwksUri) {}

  /** Browser-session cookie settings. {@code secure=false} is for plain-http local testing only. */
  public record Cookie(boolean secure) {}
}
