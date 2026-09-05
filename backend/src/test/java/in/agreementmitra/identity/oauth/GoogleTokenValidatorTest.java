package in.agreementmitra.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.oauth.GoogleTokenValidator.GoogleIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * ID-token claim validation (task 5.1), with a stubbed {@link JwtDecoder} and no network. A
 * well-formed token is accepted; each single defect (bad audience, expired, unverified email, wrong
 * issuer, bad signature) is rejected -- and no defect leaks into the exception surface beyond a
 * generic message.
 */
class GoogleTokenValidatorTest {

  private static final String ISSUER = "https://accounts.google.com";
  private static final String CLIENT_ID = "my-client-id.apps.googleusercontent.com";
  private static final String TOKEN = "the-id-token";

  private final JwtDecoder decoder = mock(JwtDecoder.class);
  private final GoogleTokenValidator validator = new GoogleTokenValidator(decoder, props());

  private static AuthProperties props() {
    return new AuthProperties(
        "pepper",
        Duration.ofHours(1),
        Duration.ofSeconds(60),
        Duration.ofMinutes(5),
        new AuthProperties.Google(
            CLIENT_ID, "secret", "redirect", "spa", ISSUER, "auth", "token", "jwks"));
  }

  private static Jwt.Builder wellFormed() {
    return Jwt.withTokenValue(TOKEN)
        .header("alg", "RS256")
        .subject("google-subject-123")
        .issuer(ISSUER)
        .audience(List.of(CLIENT_ID))
        .claim("email", "alice@gmail.com")
        .claim("email_verified", true)
        .claim("name", "Alice Example")
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300));
  }

  private void decodes(Jwt jwt) {
    when(decoder.decode(TOKEN)).thenReturn(jwt);
  }

  @Test
  void acceptsAWellFormedToken() {
    decodes(wellFormed().build());

    GoogleIdentity identity = validator.validate(TOKEN);

    assertThat(identity.subject()).isEqualTo("google-subject-123");
    assertThat(identity.email()).isEqualTo("alice@gmail.com");
    assertThat(identity.name()).isEqualTo("Alice Example");
  }

  @Test
  void rejectsAWrongAudience() {
    decodes(wellFormed().audience(List.of("some-other-client")).build());

    assertThatThrownBy(() -> validator.validate(TOKEN)).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void rejectsAnExpiredToken() {
    decodes(
        wellFormed()
            .issuedAt(Instant.now().minusSeconds(600))
            .expiresAt(Instant.now().minusSeconds(300))
            .build());

    assertThatThrownBy(() -> validator.validate(TOKEN)).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void rejectsAnUnverifiedEmail() {
    decodes(wellFormed().claim("email_verified", false).build());

    assertThatThrownBy(() -> validator.validate(TOKEN)).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void rejectsAWrongIssuer() {
    decodes(wellFormed().issuer("https://accounts.evil.example").build());

    assertThatThrownBy(() -> validator.validate(TOKEN)).isInstanceOf(InvalidLoginException.class);
  }

  @Test
  void rejectsABadSignatureFromTheDecoder() {
    when(decoder.decode(TOKEN)).thenThrow(new JwtException("bad signature"));

    assertThatThrownBy(() -> validator.validate(TOKEN)).isInstanceOf(InvalidLoginException.class);
  }
}
