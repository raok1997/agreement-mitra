package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OperatingEntityTest {

  /**
   * The specimen firm GSTIN published in GSTN's own documentation. An outside value, so the check
   * character algorithm is verified rather than round-tripped through itself.
   */
  private static final String PUBLISHED_FIRM_GSTIN = "27AAPFU0939F1ZV";

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  void blankLlpinAndGstinAreAbsent(String blank) {
    OperatingEntity entity = new OperatingEntity(blank, blank);

    assertThat(entity.llpin()).isEmpty();
    assertThat(entity.gstin()).isEmpty();
  }

  @Test
  void wellFormedLlpinIsAcceptedAndStripped() {
    assertThat(new OperatingEntity(" ACA-1234 ", null).llpin()).contains("ACA-1234");
  }

  @Test
  void malformedLlpinIsRefusedWithoutEchoingTheValue() {
    assertThatThrownBy(() -> new OperatingEntity("LLPIN-PENDING", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operator.llpin")
        .hasMessageNotContaining("LLPIN-PENDING");
  }

  @Test
  void publishedFirmGstinIsAccepted() {
    assertThat(new OperatingEntity(null, PUBLISHED_FIRM_GSTIN).gstin())
        .contains(PUBLISHED_FIRM_GSTIN);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "XXXXXXXXXXXXXXX",
        // the published GSTIN with its check character changed
        "27AAPFU0939F1ZA",
        // holder type P (an individual), with a valid check character
        "27AAPPU0939F1ZA"
      })
  void placeholderOrWrongGstinIsRefusedWithoutEchoingTheValue(String gstin) {
    assertThatThrownBy(() -> new OperatingEntity(null, gstin))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("operator.gstin")
        .hasMessageNotContaining(gstin);
  }

  @Test
  void theHolderTypeFixtureFailsOnlyOnHolderType() {
    String individual = "27AAPPU0939F1ZA";
    assertThat(OperatingEntity.checkCharacter(individual)).isEqualTo(individual.charAt(14));
  }

  @Test
  void theLegalNameIsTheCommittedConstant() {
    assertThat(new OperatingEntity(null, null).legalName()).isEqualTo("KAVISAT TEK LABS LLP");
  }

  @Test
  void toStringCarriesNoIdentifier() {
    assertThat(new OperatingEntity("ACA-1234", PUBLISHED_FIRM_GSTIN).toString())
        .doesNotContain("ACA-1234")
        .doesNotContain(PUBLISHED_FIRM_GSTIN);
  }
}
