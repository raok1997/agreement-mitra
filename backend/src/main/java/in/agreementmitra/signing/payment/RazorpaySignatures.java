package in.agreementmitra.signing.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two Razorpay signatures. They are <b>different digests over different inputs keyed by
 * different secrets</b>, and this class exists so they can never be crossed (design D2):
 *
 * <ul>
 *   <li><b>Webhook</b> - {@code X-Razorpay-Signature} is HMAC-SHA256 over the <b>raw request
 *       body</b>, keyed by the <b>webhook secret</b>. Authoritative: a verified webhook is what
 *       marks an agreement paid.
 *   <li><b>Checkout handler</b> - HMAC-SHA256 over {@code order_id + "|" + payment_id}, keyed by
 *       the <b>API key secret</b>. A user-experience signal only: it proves Razorpay
 *       <em>issued</em> the value, not that it is current, and it travels through the user's
 *       browser.
 * </ul>
 *
 * <p><b>The body must arrive byte-for-byte</b> (design D3). Verification takes the raw string the
 * container read, never a re-serialised form: Jackson round-tripping changes key order, whitespace,
 * and number formatting, so the digest would never match. That is the single most common cause of
 * "signature mismatch" in Razorpay integrations.
 *
 * <p>Comparison is <b>constant-time</b>. Both sides are hashed first and the fixed-width digests
 * compared with {@link MessageDigest#isEqual}, which short-circuits on a length mismatch - hashing
 * removes the length from the comparison entirely, so a presented value's length cannot be probed
 * by timing.
 *
 * <p>A blank secret verifies <b>nothing</b>: an unconfigured deployment rejects every webhook
 * rather than accepting any.
 */
final class RazorpaySignatures {

  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private RazorpaySignatures() {}

  /**
   * Verify a webhook: HMAC-SHA256 of the <b>raw body</b> under the <b>webhook secret</b>.
   *
   * @param rawBody exactly the bytes received, not a re-serialised form
   * @param presentedSignature the {@code X-Razorpay-Signature} header value; null/blank fails
   * @param webhookSecret the webhook signing secret; null/blank fails (closed)
   */
  static boolean webhookSignatureValid(
      String rawBody, String presentedSignature, String webhookSecret) {
    if (rawBody == null || isBlank(presentedSignature) || isBlank(webhookSecret)) {
      return false;
    }
    return constantTimeEquals(presentedSignature, hmacSha256Hex(rawBody, webhookSecret));
  }

  /**
   * Verify the value Checkout hands back to the browser: HMAC-SHA256 of {@code order_id|payment_id}
   * under the <b>API key secret</b>. Note the key: using the webhook secret here (or the key secret
   * above) is the classic crossed-wires bug.
   *
   * <p>A {@code true} result means "Razorpay issued this". It does <b>not</b> mean the payment is
   * current, captured, or even that it reached us - so it must never, on its own, mark an agreement
   * paid.
   */
  static boolean handlerSignatureValid(
      String providerOrderId,
      String providerPaymentId,
      String presentedSignature,
      String keySecret) {
    if (isBlank(providerOrderId)
        || isBlank(providerPaymentId)
        || isBlank(presentedSignature)
        || isBlank(keySecret)) {
      return false;
    }
    String payload = providerOrderId + "|" + providerPaymentId;
    return constantTimeEquals(presentedSignature, hmacSha256Hex(payload, keySecret));
  }

  /** Lowercase hex HMAC-SHA256, the encoding Razorpay uses for both signatures. */
  static String hmacSha256Hex(String payload, String key) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
      // A JRE without HMAC-SHA256 is not a condition to degrade gracefully for.
      throw new IllegalStateException("HMAC-SHA256 unavailable");
    }
  }

  /**
   * Constant-time comparison. Hashing both sides first keeps the length of the presented value out
   * of the timing signal, and keeps the comparison itself fixed-width.
   */
  static boolean constantTimeEquals(String presented, String expected) {
    return MessageDigest.isEqual(sha256(presented), sha256(expected));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private static String hex(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int value = bytes[i] & 0xFF;
      out[i * 2] = HEX[value >>> 4];
      out[i * 2 + 1] = HEX[value & 0x0F];
    }
    return new String(out);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
