package in.agreementmitra.signing.agreement;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which jurisdictions may reach <b>paid fulfilment</b>, bound from {@code jurisdiction.*}.
 * Non-secret throughout.
 *
 * <p>An <b>allowlist</b>, deliberately not a denylist of the national dimension. A denylist would
 * pass every test written today and be silently wrong the moment a third state appears: it would
 * default to eligible with nothing computing its duty. A jurisdiction is ineligible until someone
 * deliberately admits it, which is the correct default for a legal-fulfilment capability.
 *
 * @param eligible state codes eligible for paid fulfilment. <b>Empty or absent refuses
 *     everything</b> - a misconfigured deployment stops paid fulfilment rather than opening it,
 *     which is the right failure direction for legal infrastructure. The compact constructor
 *     null-normalizes so an absent configuration block refuses cleanly rather than throwing a
 *     {@code NullPointerException} into a 500: fail-closed is an implementation obligation here,
 *     not a free property of {@code @ConfigurationProperties}.
 */
@ConfigurationProperties(prefix = "jurisdiction")
record JurisdictionProperties(Set<String> eligible) {

  /**
   * The national dimension. Never admissible: stamp duty is state law and there is no national
   * rate, so there is no duty to compute and no state in which to buy a certificate. Filtered out
   * here rather than merely omitted from the default, so a well-meaning configuration edit cannot
   * re-open the hazard this allowlist exists to close.
   */
  static final String NATIONAL = "IN";

  JurisdictionProperties {
    eligible = normalize(eligible);
  }

  /**
   * Trim, upper-case, drop blanks, and drop the national dimension.
   *
   * <p>The case handling is load-bearing, not defensive tidying: catalog codes are upper-case,
   * taken verbatim from the layer-set filename ({@code TemplateCatalogSeeder.stateCodeOf}), so an
   * un-normalized {@code tg} in configuration would match nothing and refuse every agreement - a
   * fail-closed outage caused by a lower-case letter.
   */
  private static Set<String> normalize(Set<String> raw) {
    if (raw == null) {
      return Set.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String code : raw) {
      if (code == null || code.isBlank()) {
        continue;
      }
      String upper = code.trim().toUpperCase(Locale.ROOT);
      if (!NATIONAL.equals(upper)) {
        normalized.add(upper);
      }
    }
    return Set.copyOf(normalized);
  }
}
