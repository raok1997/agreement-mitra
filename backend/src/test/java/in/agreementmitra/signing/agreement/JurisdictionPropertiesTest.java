package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The allowlist's binding rules. These are not cosmetic: each case below is a way the gate could
 * silently fail open (or fail closed for the wrong reason) in a real deployment.
 */
class JurisdictionPropertiesTest {

  @Test
  @DisplayName("an absent allowlist binds to empty, so the gate refuses cleanly instead of NPE-ing")
  void absentBindsToEmpty() {
    // Fail-closed is an IMPLEMENTATION obligation, not a free property of @ConfigurationProperties:
    // without this normalization an absent config block yields a null Set and the gate's
    // .contains() throws a 500 rather than refusing.
    assertThat(new JurisdictionProperties(null).eligible()).isEmpty();
  }

  @Test
  @DisplayName("an empty allowlist stays empty, refusing every jurisdiction")
  void emptyStaysEmpty() {
    assertThat(new JurisdictionProperties(Set.of()).eligible()).isEmpty();
  }

  @Test
  @DisplayName("codes are trimmed and upper-cased, so a lower-case config value still matches")
  void normalizesCaseAndWhitespace() {
    // Catalog codes are upper-case, taken verbatim from the layer-set filename. Without this, a
    // config value of "tg" would match nothing and refuse every agreement -- a fail-closed outage
    // caused by a lower-case letter.
    assertThat(new JurisdictionProperties(Set.of(" tg ", "Ka")).eligible())
        .containsExactlyInAnyOrder("TG", "KA");
  }

  @Test
  @DisplayName("blank and null entries are dropped rather than becoming a jurisdiction")
  void dropsBlanks() {
    Set<String> raw = new HashSet<>();
    raw.add("TG");
    raw.add("  ");
    raw.add(null);
    assertThat(new JurisdictionProperties(raw).eligible()).containsExactly("TG");
  }

  @Test
  @DisplayName("the national dimension cannot be admitted, even when explicitly configured")
  void nationalIsNeverAdmissible() {
    // A property of the RULE, not of the default config: stamp duty is state law and there is no
    // national rate, so a well-meaning config edit must not be able to re-open the hazard.
    assertThat(new JurisdictionProperties(Set.of("IN", "in", " In ", "TG")).eligible())
        .containsExactly("TG");
  }

  @Test
  @DisplayName("an ordinary state code IS admitted by configuration alone, with no code change")
  void ordinaryStateIsAdmittedByConfig() {
    assertThat(new JurisdictionProperties(Set.of("TG", "KA")).eligible())
        .containsExactlyInAnyOrder("TG", "KA");
  }
}
