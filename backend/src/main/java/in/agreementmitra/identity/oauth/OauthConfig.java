package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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
    // Signature validation against Google's JWKS; issuer/audience/expiry are enforced in code by
    // GoogleTokenValidator so they stay unit-testable with a stubbed decoder. Building the decoder
    // needs only the JWKS URI, not the client credentials.
    return NimbusJwtDecoder.withJwkSetUri(google.jwksUri()).build();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
