package in.agreementmitra.signing.leegality;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Leegality adapter configuration, bound from {@code esign.leegality.*}. Secrets ({@code baseUrl},
 * {@code authToken}, {@code webhookSecret}) come from env vars; {@code profileId} and {@code
 * artifactHosts} are non-secret config. Internal to the signing module.
 *
 * <p>{@code baseUrl} is the host root only (e.g. {@code https://sandbox.leegality.com/api/}); the
 * adapter appends the per-endpoint version (create = {@code v3.0}, details = {@code v3.3}).
 *
 * @param artifactHosts the SSRF allowlist for signed-document / audit-trail URLs taken out of a
 *     provider response. Empty means "the API host only", preserving the original single-host pin
 *     for a deployment that never configures it.
 */
@ConfigurationProperties(prefix = "esign.leegality")
record LeegalityProperties(
    String baseUrl,
    String authToken,
    String webhookSecret,
    String profileId,
    List<String> artifactHosts) {

  LeegalityProperties {
    artifactHosts = artifactHosts == null ? List.of() : List.copyOf(artifactHosts);
  }

  /**
   * Convenience for tests that do not configure an artifact allowlist. Deliberately a static
   * factory and not a second constructor: {@code @ConfigurationProperties} value-object binding
   * needs an unambiguous constructor, and a second one makes Spring fall back to looking for a
   * no-arg one.
   */
  static LeegalityProperties of(
      String baseUrl, String authToken, String webhookSecret, String profileId) {
    return new LeegalityProperties(baseUrl, authToken, webhookSecret, profileId, List.of());
  }

  /** Never renders the auth token or the webhook secret. */
  @Override
  public String toString() {
    return "LeegalityProperties{baseUrl=" + baseUrl + ", profileId=" + profileId + "}";
  }
}
