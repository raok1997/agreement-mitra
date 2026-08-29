package in.agreementmitra.identity.support;

import in.agreementmitra.identity.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Keyed one-way hashing for the opaque secrets the identity module persists: the session value, the
 * single-use handoff code, and the OAuth {@code state}. Only the hash is ever stored; a presented
 * value is authenticated by re-hashing and looking the hash up.
 *
 * <p>HMAC-SHA256 with a server-side pepper (env-sourced, {@link AuthProperties#hashPepper()})
 * rather than a bare digest: the pepper means a leaked database alone cannot be brute-forced
 * offline against the (already high-entropy) values. Output is URL-safe Base64 (no padding) so it
 * fits a {@code text} column and never needs escaping. Public so the module's internal sub-packages
 * share one implementation; internal package, so no other module sees it.
 */
@Component
public class TokenHasher {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] pepper;

  public TokenHasher(AuthProperties properties) {
    this.pepper = properties.hashPepper().getBytes(StandardCharsets.UTF_8);
  }

  /** Keyed hash of a raw secret value, as URL-safe Base64 (no padding). Never logs the input. */
  public String hash(String rawValue) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(pepper, ALGORITHM));
      byte[] digest = mac.doFinal(rawValue.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      // HmacSHA256 is guaranteed present on every JVM; a failure here is a broken runtime.
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  /**
   * Constant-time comparison of two already-computed hashes. Used where a candidate hash is
   * compared in memory; {@link MessageDigest#isEqual} does not short-circuit on the first differing
   * byte, so it does not leak match length through timing.
   */
  public boolean constantTimeEquals(String a, String b) {
    if (a == null || b == null) {
      return false;
    }
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
