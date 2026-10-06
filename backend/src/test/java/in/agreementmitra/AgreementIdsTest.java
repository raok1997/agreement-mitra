package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Agreement-id redaction format (agreement-id-debug-logging 1.2). */
class AgreementIdsTest {

  private static final UUID ID = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
  private static final UUID OTHER = UUID.fromString("9f8e7d6c-5b4a-4321-8fed-cba987654321");

  @Test
  void redactKeepsTheFirstEightHexPlusAnEllipsis() {
    assertThat(AgreementIds.redact(ID)).isEqualTo("1a2b3c4d…");
  }

  @Test
  void redactOfNullIsTheLiteralNull() {
    assertThat(AgreementIds.redact(null)).isEqualTo("null");
  }

  @Test
  void redactInRewritesABlobKey() {
    assertThat(AgreementIds.redactIn("drafts/" + ID + ".pdf")).isEqualTo("drafts/1a2b3c4d….pdf");
  }

  @Test
  void redactInRewritesEveryUuidInAMessage() {
    assertThat(AgreementIds.redactIn("moved " + ID + " to " + OTHER))
        .isEqualTo("moved 1a2b3c4d… to 9f8e7d6c…");
  }

  @Test
  void redactInMatchesUppercaseAndRendersTheCanonicalLowercasePrefix() {
    assertThat(AgreementIds.redactIn("id=" + ID.toString().toUpperCase()))
        .isEqualTo("id=1a2b3c4d…");
  }

  @Test
  void redactInLeavesTextWithoutAUuidUnchanged() {
    assertThat(AgreementIds.redactIn("stamped/abc.pdf")).isEqualTo("stamped/abc.pdf");
  }

  @Test
  void redactInOfNullIsNull() {
    assertThat(AgreementIds.redactIn(null)).isNull();
  }

  @Test
  void redactAndRedactInAgreeOnTheSameId() {
    assertThat(AgreementIds.redactIn(ID.toString())).isEqualTo(AgreementIds.redact(ID));
  }
}
