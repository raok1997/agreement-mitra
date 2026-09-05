package in.agreementmitra.signing.stamp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CertificateNumbers}. Normalisation is a correctness property, not
 * cosmetics: the database's single-use ledger indexes the normalised value, so two spellings of one
 * purchased certificate must collapse to one key or the stamp gets spent twice.
 */
class CertificateNumbersTest {

  @Test
  void casingAndSurroundingWhitespaceCollapseToOneValue() {
    assertThat(CertificateNumbers.normalize("  in-ka12345678901234x  "))
        .isEqualTo(CertificateNumbers.normalize("IN-KA12345678901234X"))
        .isEqualTo("IN-KA12345678901234X");
  }

  @Test
  void internalWhitespaceRunsCollapseToASingleSpace() {
    assertThat(CertificateNumbers.normalize("IN KA   1234 5678"))
        .isEqualTo(CertificateNumbers.normalize("IN KA 1234 5678"));
  }

  @Test
  void normalizeIsNullSafe() {
    assertThat(CertificateNumbers.normalize(null)).isNull();
  }

  @Test
  void wellFormedAcceptsRealisticShcilShapes() {
    assertThat(CertificateNumbers.isWellFormed("IN-KA12345678901234X")).isTrue();
    assertThat(CertificateNumbers.isWellFormed("IN KA 1234 5678")).isTrue();
    assertThat(CertificateNumbers.isWellFormed("KA/2026/000123")).isTrue();
  }

  @Test
  void wellFormedRejectsControlCharactersAndOversizeValues() {
    assertThat(CertificateNumbers.isWellFormed("IN-KA\n1234")).isFalse();
    assertThat(CertificateNumbers.isWellFormed("SHORT")).isFalse();
    assertThat(CertificateNumbers.isWellFormed("A".repeat(65))).isFalse();
    assertThat(CertificateNumbers.isWellFormed(null)).isFalse();
  }

  @Test
  void redactionExposesOnlyTheLastFourCharacters() {
    assertThat(CertificateNumbers.redact("IN-KA12345678901234X")).isEqualTo("***234X");
    assertThat(CertificateNumbers.redact("IN-KA12345678901234X"))
        .doesNotContain("IN-KA")
        .doesNotContain("12345678");
  }

  @Test
  void redactionOfAbsentOrShortValuesLeaksNothingAtAll() {
    assertThat(CertificateNumbers.redact(null)).isEqualTo("***");
    assertThat(CertificateNumbers.redact("   ")).isEqualTo("***");
    assertThat(CertificateNumbers.redact("AB12")).isEqualTo("***");
  }
}
