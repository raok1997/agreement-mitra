package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import java.net.URI;
import java.net.URISyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Wires the identity module's OAuth configuration: enables {@link AuthProperties} and builds the
 * {@link JwtDecoder} used to validate Google ID tokens against Google's published JWKS. Internal to
 * the module.
 *
 * <p><b>Login is optional, so this never blocks application startup.</b> When the Google client
 * id/secret are absent the app still boots (anonymous drafting, templates, signing all work) --
 * only the login handshake is unavailable, and it fails closed at request time in {@link
 * GoogleLoginService} (it never falls back to a real Google project). A blank config is surfaced as
 * a one-line WARN at startup so it is discoverable. The decoder itself needs only the JWKS URI
 * (which has a default), so it builds regardless; it is {@link ConditionalOnMissingBean} so an
 * integration test can substitute one bound to a stubbed JWKS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class OauthConfig {

  private static final Logger log = LoggerFactory.getLogger(OauthConfig.class);

  @Bean
  @ConditionalOnMissingBean
  JwtDecoder googleIdTokenDecoder(AuthProperties properties) {
    AuthProperties.Google google = properties.google();
    if (isBlank(google.clientId()) || isBlank(google.clientSecret())) {
      log.warn(
          "Google login is DISABLED: set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_SECRET to enable "
              + "it. The app runs normally without them (login is optional); the handshake fails "
              + "closed until they are configured.");
    }
    warnIfCallbackHostsDiffer(google);
    // Signature validation against Google's JWKS; issuer/audience/expiry are enforced in code by
    // GoogleTokenValidator so they stay unit-testable with a stubbed decoder. Building the decoder
    // needs only the JWKS URI, not the client credentials.
    // The JWKS fetch runs inside the callback transaction, so it gets the same bounded timeouts as
    // the token POST (GoogleHttp).
    return NimbusJwtDecoder.withJwkSetUri(google.jwksUri())
        .restOperations(new RestTemplate(GoogleHttp.requestFactory()))
        .build();
  }

  /**
   * The login-binding cookie is host-only (login-browser-binding D1), so the Google callback and
   * the SPA route that runs the exchange must share a host -- otherwise every login ends on the
   * SPA's error screen with only a DEBUG trace. A WARN, not a refusal: login is optional and must
   * never block startup.
   */
  static boolean warnIfCallbackHostsDiffer(AuthProperties.Google google) {
    String callbackHost = hostOf(google.redirectUri());
    String spaHost = hostOf(google.spaCallbackUri());
    if (callbackHost == null || spaHost == null || callbackHost.equalsIgnoreCase(spaHost)) {
      return false;
    }
    log.warn(
        "Google login will fail: the OAuth callback host ({}) differs from the SPA callback host "
            + "({}), and the host-only login-binding cookie cannot reach both. Serve them on one "
            + "host.",
        callbackHost,
        spaHost);
    return true;
  }

  private static String hostOf(String uri) {
    if (isBlank(uri)) {
      return null;
    }
    try {
      return new URI(uri.trim()).getHost();
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
