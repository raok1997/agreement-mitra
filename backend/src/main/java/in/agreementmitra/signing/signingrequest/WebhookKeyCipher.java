package in.agreementmitra.signing.signingrequest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts the per-transaction webhook key at rest (design D3).
 *
 * <p>ZOOP eSign v5 issues a {@code webhook_security_key} per transaction and authenticates its
 * callback by presenting that value in an HTTP header. It is a <b>credential</b> that authenticates
 * inbound state changes for the life of the transaction, not metadata - so a database read must not
 * yield a working webhook credential. Encrypted here before it is ever stored, decrypted only in
 * memory at verification time, and never logged in any form.
 *
 * <p>AES-256-GCM with a fresh random 96-bit IV per encryption; the stored value is {@code base64(iv
 * || ciphertext||tag)}. GCM is authenticated encryption, so a tampered ciphertext fails to decrypt
 * rather than yielding attacker-chosen plaintext. The key is derived by SHA-256 over the configured
 * secret, which comes from an environment variable in any shared environment.
 *
 * <p>Package-private: nothing outside the signing-request persistence path needs it.
 */
@Component
@EnableConfigurationProperties(WebhookKeyProperties.class)
class WebhookKeyCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String ALGORITHM = "AES";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  WebhookKeyCipher(WebhookKeyProperties properties) {
    this.key = new SecretKeySpec(derive(properties.pepper()), ALGORITHM);
  }

  /**
   * Encrypt a per-transaction webhook key for storage. Null in, null out - a provider that issues
   * no such credential stores nothing.
   */
  String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isEmpty()) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] envelope = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, envelope, 0, iv.length);
      System.arraycopy(ciphertext, 0, envelope, iv.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(envelope);
    } catch (GeneralSecurityException e) {
      // Never include the plaintext (or the exception's message, which can echo input) in the
      // fault.
      throw new IllegalStateException("Webhook key encryption failed");
    }
  }

  /**
   * Decrypt a stored per-transaction webhook key. Null/blank in, null out. A stored value that
   * cannot be decrypted (wrong secret, tampered row) yields null rather than throwing, so
   * verification simply fails closed instead of turning a bad row into a 500 on a public endpoint.
   */
  String decrypt(String stored) {
    if (stored == null || stored.isBlank()) {
      return null;
    }
    try {
      byte[] envelope = Base64.getDecoder().decode(stored);
      if (envelope.length <= IV_BYTES) {
        return null;
      }
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(envelope, 0, iv, 0, IV_BYTES);
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] plaintext = cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return null; // fail closed: an undecryptable key verifies nothing
    }
  }

  private static byte[] derive(String pepper) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest((pepper == null ? "" : pepper).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }
}
