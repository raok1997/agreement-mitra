package in.agreementmitra.signing.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay adapter configuration, bound from {@code payment.razorpay.*}. Internal to the signing
 * module.
 *
 * <p><b>Three credentials, and two of them are secrets.</b>
 *
 * <ul>
 *   <li>{@code keyId} is <b>public by design</b> - it is handed to the browser so Checkout can
 *       open. It is not a secret and is the only one that ever leaves the server.
 *   <li>{@code keySecret} is server-only. It authenticates our API calls (HTTP Basic, paired with
 *       the key id) and signs the value Checkout hands back to the browser.
 *   <li>{@code webhookSecret} is server-only and <b>distinct from the key secret</b> (design D2).
 *       It, and only it, verifies inbound webhooks.
 * </ul>
 *
 * <p>The two secrets are separate properties with separate names and <b>neither defaults to the
 * other</b>. Using the key secret to validate webhooks is a well-trodden integration error: it
 * produces a verifier that rejects every legitimate webhook, or - if the codepaths are crossed -
 * one that accepts forged ones. Making them structurally distinct removes the possibility.
 *
 * <p>Both secrets come from environment variables only and are never committed, never returned by
 * any endpoint, and never logged. This repository is <b>test mode only</b>: the checked-in key id
 * default is empty and the live host is never a default.
 *
 * @param baseUrl the API root; defaults to Razorpay's single API host (test and live are selected
 *     by the credentials, not by the host)
 * @param keyId the public key identifier ({@code rzp_test_...} here), sent to the browser
 * @param keySecret the API key secret (env only; Basic-auth password and handler-signature key)
 * @param webhookSecret the webhook signing secret (env only; verifies {@code X-Razorpay-Signature})
 */
@ConfigurationProperties(prefix = "payment.razorpay")
record RazorpayProperties(String baseUrl, String keyId, String keySecret, String webhookSecret) {

  private static final String DEFAULT_BASE_URL = "https://api.razorpay.com/";

  RazorpayProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
  }

  /**
   * Whether API calls can be made at all. Order creation refuses cleanly rather than half-trying.
   */
  boolean apiConfigured() {
    return present(keyId) && present(keySecret);
  }

  /** Whether inbound webhooks can be verified. Absent means every webhook is rejected (closed). */
  boolean webhookConfigured() {
    return present(webhookSecret);
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  /** Never renders a credential - not even a prefix of one. */
  @Override
  public String toString() {
    return "RazorpayProperties{baseUrl="
        + baseUrl
        + ", apiConfigured="
        + apiConfigured()
        + ", webhookConfigured="
        + webhookConfigured()
        + "}";
  }
}
