package in.agreementmitra.signing.zoop;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ZOOP eSign v5 adapter configuration, bound from {@code esign.zoop.*}. Internal to the signing
 * module.
 *
 * <p>Credentials ({@code appId}, {@code apiKey}) come from environment variables only and are never
 * committed. The <b>production host is never a default</b>: the checked-in default is the free,
 * self-serve test environment, so a misconfigured deployment talks to the sandbox rather than
 * silently burning real signature credit.
 *
 * @param baseUrl the API root, e.g. {@code https://test.zoop.plus/contract/esign/}; the adapter
 *     appends the {@code v5/...} paths
 * @param appId the {@code app-id} auth header value (env only)
 * @param apiKey the {@code api-key} auth header value (env only)
 * @param txnExpiryMin how long a transaction stays signable, in minutes. Non-secret config, and
 *     deliberately measured in <b>days</b> in practice (the vendor's own samples use 10080 = 7
 *     days): the two parties sign days apart, so a window measured in minutes would be unusable.
 * @param artifactHosts the SSRF allowlist for signed-document / audit-trail URLs. ZOOP hands back
 *     expiring links on a host that differs from the API host, so this is an allowlist rather than
 *     a single pin - but it stays an allowlist (design D8).
 * @param responseUrl the publicly reachable webhook URL ZOOP posts completions to
 * @param redirectUrl where a signer lands after signing
 * @param orgName the organisation name shown in the invitation email ZOOP sends
 */
@ConfigurationProperties(prefix = "esign.zoop")
record ZoopProperties(
    String baseUrl,
    String appId,
    String apiKey,
    Integer txnExpiryMin,
    List<String> artifactHosts,
    String responseUrl,
    String redirectUrl,
    String orgName) {

  /** 7 days, matching the vendor's own samples - the parties genuinely do sign days apart. */
  private static final int DEFAULT_EXPIRY_MINUTES = 10080;

  ZoopProperties {
    txnExpiryMin =
        txnExpiryMin == null || txnExpiryMin <= 0 ? DEFAULT_EXPIRY_MINUTES : txnExpiryMin;
    artifactHosts = artifactHosts == null ? List.of() : List.copyOf(artifactHosts);
    orgName = orgName == null || orgName.isBlank() ? "AgreementMitra" : orgName;
  }

  /** Never renders the credentials. */
  @Override
  public String toString() {
    return "ZoopProperties{baseUrl=" + baseUrl + ", txnExpiryMin=" + txnExpiryMin + "}";
  }
}
