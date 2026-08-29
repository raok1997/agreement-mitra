package in.agreementmitra.identity;

import java.time.Duration;
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
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    String hashPepper,
    Duration sessionTtl,
    Duration handoffTtl,
    Duration loginStateTtl,
    Google google) {

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
}
