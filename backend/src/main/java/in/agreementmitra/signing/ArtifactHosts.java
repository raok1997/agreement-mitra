package in.agreementmitra.signing;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;

/**
 * SSRF guard for provider-supplied artifact URLs, shared by every {@link EsignProvider} adapter.
 *
 * <p>A signed document and its audit trail arrive as URLs inside a vendor response, which is
 * attacker-influenced input as far as our process is concerned. Before fetching one, its host must
 * appear in a <b>configured allowlist</b> of provider hosts.
 *
 * <p>An allowlist rather than a single pinned host (design D8): ZOOP's {@code complete_signed_url}
 * is an expiring link on a different host from its API ({@code esign.zoop.plus} vs {@code
 * test.zoop.plus}), so single-host pinning would break a legitimate download. It must stay an
 * allowlist - never weakened to "any https URL the vendor sent", which is not a guard at all.
 */
public final class ArtifactHosts {

  private ArtifactHosts() {}

  /**
   * Throw unless {@code url}'s host is one of {@code allowedHosts} (case-insensitive). An empty
   * allowlist refuses everything - fail closed, so a misconfiguration cannot silently open an
   * arbitrary outbound fetch.
   *
   * @throws IllegalStateException if the URL is unparseable, hostless, or on an unlisted host
   */
  public static void require(String url, Collection<String> allowedHosts) {
    String host;
    try {
      host = URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      // Never echo the URL: it may be a bearer capability.
      throw new IllegalStateException("Artifact URL is not a valid URI");
    }
    if (host == null
        || allowedHosts == null
        || allowedHosts.isEmpty()
        || !contains(allowedHosts, host)) {
      throw new IllegalStateException("Artifact URL host is not an allowed provider host");
    }
  }

  private static boolean contains(Collection<String> allowedHosts, String host) {
    String needle = host.toLowerCase(Locale.ROOT);
    return allowedHosts.stream()
        .filter(allowed -> allowed != null && !allowed.isBlank())
        .anyMatch(allowed -> allowed.trim().toLowerCase(Locale.ROOT).equals(needle));
  }
}
