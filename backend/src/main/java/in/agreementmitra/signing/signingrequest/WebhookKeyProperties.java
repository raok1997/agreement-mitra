package in.agreementmitra.signing.signingrequest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The application-side pepper that keys the encryption of per-transaction webhook keys at rest,
 * bound from {@code esign.webhook-key.*}. From an environment variable in any shared or real
 * environment; the checked-in default is a sandbox-only placeholder, exactly like {@code
 * auth.hash-pepper}.
 *
 * @param pepper input to the AES key derivation; never logged, never returned by any API
 */
@ConfigurationProperties(prefix = "esign.webhook-key")
record WebhookKeyProperties(String pepper) {

  /** Never renders the value. */
  @Override
  public String toString() {
    return "WebhookKeyProperties{pepper=****}";
  }
}
