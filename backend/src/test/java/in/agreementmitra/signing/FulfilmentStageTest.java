package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.api.AgreementDisplayStatus;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the fulfilment-stage projection in isolation -- no Spring, no DB. The stage exists
 * because the list's display status collapses the three states a customer most wants to tell apart;
 * these tests pin that they stay apart, and that "terminal" means exactly the four states the FSM
 * cannot leave.
 */
class FulfilmentStageTest {

  @Test
  void noSigningRequestIsNotStarted() {
    assertThat(FulfilmentStage.from(Optional.empty())).isEqualTo(FulfilmentStage.NOT_STARTED);
  }

  @Test
  void theThreeInProgressStatesStayDistinct() {
    FulfilmentStage awaiting = FulfilmentStage.from(Optional.of(SignatureStatus.PDF_GENERATED));
    FulfilmentStage stamped = FulfilmentStage.from(Optional.of(SignatureStatus.STAMPED));
    FulfilmentStage out = FulfilmentStage.from(Optional.of(SignatureStatus.SIGN_REQUESTED));

    assertThat(awaiting).isEqualTo(FulfilmentStage.AWAITING_STAMP);
    assertThat(stamped).isEqualTo(FulfilmentStage.STAMPED);
    assertThat(out).isEqualTo(FulfilmentStage.OUT_FOR_SIGNATURE);
    assertThat(EnumSet.of(awaiting, stamped, out)).hasSize(3);
  }

  @Test
  void reservedDraftReadsAsNotStarted() {
    assertThat(FulfilmentStage.from(Optional.of(SignatureStatus.DRAFT)))
        .isEqualTo(FulfilmentStage.NOT_STARTED);
  }

  @Test
  void terminalIsExactlyTheFourStatesTheFsmCannotLeave() {
    EnumSet<FulfilmentStage> terminal =
        EnumSet.of(
            FulfilmentStage.SIGNED,
            FulfilmentStage.EXPIRED,
            FulfilmentStage.FAILED,
            FulfilmentStage.STAMP_FAILED);
    for (FulfilmentStage stage : FulfilmentStage.values()) {
      assertThat(stage.terminal()).as("%s terminal", stage).isEqualTo(terminal.contains(stage));
    }
  }

  /**
   * The list's display status must remain a coarsening of the stage: for every signing status the
   * two projections agree on which bucket it lands in. If someone later adds a stage that the
   * display status cannot be derived from, this is the test that says so.
   */
  @Test
  void displayStatusIsDerivableFromTheStage() {
    // The reserved DRAFT status is the one deliberate exception: "not started" for the customer,
    // but the list still shows it as in progress because a request row exists.
    for (SignatureStatus s : EnumSet.complementOf(EnumSet.of(SignatureStatus.DRAFT))) {
      FulfilmentStage stage = FulfilmentStage.from(Optional.of(s));
      AgreementDisplayStatus expected = AgreementDisplayStatus.from(Optional.of(s));
      AgreementDisplayStatus derived =
          switch (stage) {
            case NOT_STARTED -> AgreementDisplayStatus.DRAFT;
            case AWAITING_STAMP, STAMPED, OUT_FOR_SIGNATURE -> AgreementDisplayStatus.IN_PROGRESS;
            case SIGNED -> AgreementDisplayStatus.SIGNED;
            case EXPIRED -> AgreementDisplayStatus.EXPIRED;
            case FAILED, STAMP_FAILED -> AgreementDisplayStatus.ACTION_NEEDED;
          };
      assertThat(derived).as("%s: stage %s vs display", s, stage).isEqualTo(expected);
    }
  }
}
