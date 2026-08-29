package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TrackingReference}. The check character is the single most important
 * property here: the reference travels through a manual loop, and a mistyped one must be REJECTED
 * rather than silently resolving to a different agreement - because the artifact being attached is
 * a certificate that cost real money.
 */
class TrackingReferenceTest {

  @Test
  void generatedReferencesAreWellFormedAndSelfValidating() {
    IntStream.range(0, 200)
        .forEach(
            i -> {
              String reference = TrackingReference.generate();
              assertThat(reference).hasSize(TrackingReference.LENGTH);
              assertThat(reference).startsWith(TrackingReference.PREFIX);
              assertThat(TrackingReference.isValid(reference)).isTrue();
            });
  }

  @Test
  void generatedReferencesUseNoAmbiguousGlyphs() {
    String body = TrackingReference.generate().substring(TrackingReference.PREFIX.length());
    assertThat(body).doesNotContain("0").doesNotContain("O");
    assertThat(body).doesNotContain("1").doesNotContain("I").doesNotContain("L");
  }

  @Test
  void generatedReferencesDoNotRepeatAcrossManyDraws() {
    Set<String> seen = new HashSet<>();
    IntStream.range(0, 2000).forEach(i -> seen.add(TrackingReference.generate()));
    assertThat(seen).hasSize(2000);
  }

  @Test
  void aSingleMistypedCharacterIsRejectedRatherThanResolvingElsewhere() {
    String reference = TrackingReference.generate();
    char[] mistyped = reference.toCharArray();
    int position = TrackingReference.PREFIX.length();
    mistyped[position] = mistyped[position] == '9' ? '8' : '9';
    assertThat(new String(mistyped)).isNotEqualTo(reference);
    assertThat(TrackingReference.isValid(new String(mistyped))).isFalse();
  }

  @Test
  void transposingTwoAdjacentCharactersIsRejected() {
    String reference = TrackingReference.generate();
    char[] chars = reference.toCharArray();
    int a = TrackingReference.PREFIX.length();
    int b = a + 1;
    if (chars[a] == chars[b]) {
      return; // a transposition of identical characters is not a detectable error
    }
    char swap = chars[a];
    chars[a] = chars[b];
    chars[b] = swap;
    assertThat(TrackingReference.isValid(new String(chars))).isFalse();
  }

  @Test
  void normalizeMakesLowercaseAndPaddedInputTheSameReference() {
    String reference = TrackingReference.generate();
    assertThat(TrackingReference.normalize("  " + reference.toLowerCase(Locale.ROOT) + " "))
        .isEqualTo(reference);
    assertThat(TrackingReference.isValid(TrackingReference.normalize(" " + reference + " ")))
        .isTrue();
  }

  @Test
  void normalizeIsNullSafeAndAnEmptyValueIsNotValid() {
    assertThat(TrackingReference.normalize(null)).isNull();
    assertThat(TrackingReference.isValid(null)).isFalse();
    assertThat(TrackingReference.isValid("")).isFalse();
  }

  @Test
  void theRetiredDerivedFormatIsRecognisedAndIsNeverValid() {
    String legacy = "AM-1A2B3C-010126";
    assertThat(TrackingReference.isLegacyDerivedFormat(legacy)).isTrue();
    assertThat(TrackingReference.isValid(legacy)).isFalse();
  }

  @Test
  void aLiveReferenceIsNotMistakenForTheRetiredDerivedFormat() {
    assertThat(TrackingReference.isLegacyDerivedFormat(TrackingReference.generate())).isFalse();
  }
}
