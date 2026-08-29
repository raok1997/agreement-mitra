package in.agreementmitra.identity.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Mints high-entropy opaque secrets (session values, handoff codes, OAuth {@code state}, PKCE
 * verifier/challenge) with a cryptographically strong RNG. Every value is 256 bits of randomness,
 * URL-safe Base64 without padding so it drops straight into a URL or an {@code Authorization}
 * header. Public so the module's internal sub-packages share one implementation; internal package,
 * so no other module sees it.
 */
@Component
public class SecretTokens {

  private static final int TOKEN_BYTES = 32; // 256 bits

  private final SecureRandom random = new SecureRandom();

  /** A fresh 256-bit URL-safe token. Never logged by callers. */
  public String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The PKCE S256 {@code code_challenge} for a raw {@code code_verifier}: URL-safe Base64 of
   * SHA-256(verifier), per RFC 7636. Sent to Google at {@code /start}; the matching verifier is
   * held server-side and replayed at token exchange, so an intercepted authorization code is
   * useless without it.
   */
  public String pkceChallenge(String codeVerifier) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
