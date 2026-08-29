package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.ClosureReason;
import in.agreementmitra.signing.ClosureState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Closure on the {@link Agreement} aggregate: terminal, idempotent, and always able to say why.
 *
 * <p>Idempotence is the interesting one. The completion path is re-entered by both the webhook and
 * the reconciliation job, so a second close is normal - and it must leave the original reason and
 * time alone rather than rewriting when the work finished.
 */
class AgreementClosureTest {

  private static Agreement agreement() {
    return Agreement.create(
        "12 MG Road, Bengaluru",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 12, 1));
  }

  @Test
  void aNewAgreementIsOpenWithNoClosureFacts() {
    Agreement agreement = agreement();
    assertThat(agreement.closureState()).isEqualTo(ClosureState.OPEN);
    assertThat(agreement.closedAt()).isNull();
    assertThat(agreement.closureReason()).isNull();
  }

  @Test
  void closingRecordsWhenAndWhy() {
    Agreement agreement = agreement();
    Instant at = Instant.parse("2026-08-03T10:15:30Z");

    assertThat(agreement.close(ClosureReason.COMPLETED, at)).isTrue();

    assertThat(agreement.closureState()).isEqualTo(ClosureState.CLOSED);
    assertThat(agreement.closureReason()).isEqualTo(ClosureReason.COMPLETED);
    assertThat(agreement.closedAt()).isEqualTo(at);
  }

  @Test
  void closingTwiceKeepsTheOriginalReasonAndTime() {
    Agreement agreement = agreement();
    Instant first = Instant.parse("2026-08-03T10:15:30Z");
    agreement.close(ClosureReason.COMPLETED, first);

    // A redelivered webhook, or the reconciliation job passing over an already-complete request.
    boolean closedAgain =
        agreement.close(
            ClosureReason.ABANDONED_SIGNING_FAILED, Instant.parse("2026-09-01T00:00:00Z"));

    assertThat(closedAgain).isFalse();
    assertThat(agreement.closureReason()).isEqualTo(ClosureReason.COMPLETED);
    assertThat(agreement.closedAt()).isEqualTo(first);
  }

  @Test
  void closureIsTerminalAndNeverReturnsToOpen() {
    Agreement agreement = agreement();
    agreement.close(ClosureReason.ABANDONED_STAMP_FAILED, Instant.now());

    agreement.close(ClosureReason.COMPLETED, Instant.now());

    assertThat(agreement.closureState()).isEqualTo(ClosureState.CLOSED);
    assertThat(agreement.closureReason()).isEqualTo(ClosureReason.ABANDONED_STAMP_FAILED);
  }

  @Test
  void everyAbandonmentReasonStaysDistinguishableFromCompleted() {
    for (ClosureReason reason :
        new ClosureReason[] {
          ClosureReason.ABANDONED_SIGNING_FAILED,
          ClosureReason.ABANDONED_SIGNING_EXPIRED,
          ClosureReason.ABANDONED_STAMP_FAILED
        }) {
      Agreement agreement = agreement();
      agreement.close(reason, Instant.now());
      assertThat(agreement.closureReason().abandoned())
          .as("%s must be reported as abandoned, never as completed", reason)
          .isTrue();
    }
  }

  @Test
  void closureDoesNotTouchAnythingElseOnTheAggregate() {
    // Closure means "no work outstanding" - not archival, retention expiry, or deletion. Nothing
    // stored is dropped, so the documents stay retrievable indefinitely afterwards.
    Agreement agreement = agreement();
    agreement.attachDraft("drafts/x.pdf");

    agreement.close(ClosureReason.COMPLETED, Instant.now());

    assertThat(agreement.draftPdfKey()).isEqualTo("drafts/x.pdf");
    assertThat(agreement.propertyAddress()).isEqualTo("12 MG Road, Bengaluru");
  }
}
