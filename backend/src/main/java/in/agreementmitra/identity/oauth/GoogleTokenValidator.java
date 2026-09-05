package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Validates a Google ID token before any identity is materialized. Decoding runs through the
 * injected {@link JwtDecoder} (a {@code NimbusJwtDecoder} bound to Google's JWKS in production --
 * see {@code OauthConfig}), which enforces the JWKS signature. On top of that this validator
 * enforces the claim-level rules in code -- accepted issuer, audience == our client id, unexpired
 * (small skew), and {@code email_verified == true} -- so the rules are unit-testable with a stubbed
 * decoder and no network. Any failure throws {@link InvalidLoginException}; the message never
 * echoes the token, email, or subject.
 */
@Component
class GoogleTokenValidator {

  /** Google mints tokens with either the bare or the https issuer; accept both, plus config's. */
  private static final Set<String> GOOGLE_ISSUERS =
      Set.of("https://accounts.google.com", "accounts.google.com");

  private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

  private final JwtDecoder jwtDecoder;
  private final String expectedIssuer;
  private final String expectedAudience;

  GoogleTokenValidator(JwtDecoder jwtDecoder, AuthProperties properties) {
    this.jwtDecoder = jwtDecoder;
    this.expectedIssuer = properties.google().issuer();
    this.expectedAudience = properties.google().clientId();
  }

  /**
   * Decode and fully validate the ID token, returning the verified subject/email/name. Throws
   * {@link InvalidLoginException} on any defect (bad signature, wrong issuer/audience, expired, or
   * unverified email).
   */
  GoogleIdentity validate(String idToken) {
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(idToken);
    } catch (JwtException e) {
      // Bad signature / malformed / decoder-level validator failure. Do not leak the token.
      throw new InvalidLoginException("ID token failed signature/decoder validation", e);
    }

    String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    if (issuer == null || !(issuer.equals(expectedIssuer) || GOOGLE_ISSUERS.contains(issuer))) {
      throw new InvalidLoginException("ID token issuer not accepted");
    }

    List<String> audience = jwt.getAudience();
    if (audience == null || !audience.contains(expectedAudience)) {
      throw new InvalidLoginException("ID token audience is not our client id");
    }

    Instant expiresAt = jwt.getExpiresAt();
    if (expiresAt == null || expiresAt.plus(CLOCK_SKEW).isBefore(Instant.now())) {
      throw new InvalidLoginException("ID token is expired");
    }

    if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
      throw new InvalidLoginException("ID token email is not verified");
    }

    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new InvalidLoginException("ID token has no subject");
    }
    String email = jwt.getClaimAsString("email");
    String name = jwt.getClaimAsString("name");
    return new GoogleIdentity(subject, email, name);
  }

  /** The verified claims we key an identity by. Transient (never persisted or logged verbatim). */
  record GoogleIdentity(String subject, String email, String name) {}
}
