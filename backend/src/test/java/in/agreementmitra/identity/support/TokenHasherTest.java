package in.agreementmitra.identity.support;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The keyed hasher used for session/handoff/state values (tasks 5.2, 5.3). A stored hash is never
 * the raw value, is stable for a given value + pepper, differs across values, and compares in
 * constant time.
 */
class TokenHasherTest {

  private static final AuthProperties PROPS =
      new AuthProperties(
          "unit-test-pepper",
          Duration.ofHours(1),
          Duration.ofSeconds(60),
          Duration.ofMinutes(5),
          new AuthProperties.Google(
              "client", "secret", "redirect", "spa", "issuer", "auth", "token", "jwks"));

  private final TokenHasher hasher = new TokenHasher(PROPS);

  @Test
  void hashIsNotThePlaintextValue() {
    String value = "super-secret-session-value";
    assertThat(hasher.hash(value)).isNotEqualTo(value).doesNotContain(value);
  }

  @Test
  void hashIsStableForTheSameValue() {
    assertThat(hasher.hash("abc")).isEqualTo(hasher.hash("abc"));
  }

  @Test
  void differentValuesHashDifferently() {
    assertThat(hasher.hash("abc")).isNotEqualTo(hasher.hash("abd"));
  }

  @Test
  void differentPepperHashesDifferently() {
    TokenHasher other =
        new TokenHasher(
            new AuthProperties(
                "a-different-pepper",
                Duration.ofHours(1),
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                PROPS.google()));
    assertThat(other.hash("abc")).isNotEqualTo(hasher.hash("abc"));
  }

  @Test
  void constantTimeEqualsMatchesOnlyIdenticalHashes() {
    String h = hasher.hash("abc");
    assertThat(hasher.constantTimeEquals(h, h)).isTrue();
    assertThat(hasher.constantTimeEquals(h, hasher.hash("abd"))).isFalse();
    assertThat(hasher.constantTimeEquals(h, null)).isFalse();
    assertThat(hasher.constantTimeEquals(null, h)).isFalse();
  }
}
