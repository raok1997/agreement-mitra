package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.agreementmitra.signing.UnrecordedPayment;
import in.agreementmitra.signing.UnrecordedPayment.Reason;
import in.agreementmitra.signing.payment.PaymentOrderRepository.UnrecordedAttempt;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The unrecorded-payment look-back's two halves share one list: the outcomes the query is asked
 * for, and the reason each row is reported as.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOrderQueryAdapterTest {

  @Mock private PaymentOrderRepository repository;

  private final Instant cutoff = Instant.now();

  private static UnrecordedAttempt row(UUID paymentOrderId, UUID agreementId, String outcome) {
    UnrecordedAttempt row = mock(UnrecordedAttempt.class);
    when(row.getPaymentOrderId()).thenReturn(paymentOrderId);
    when(row.getAgreementId()).thenReturn(agreementId);
    when(row.getOutcome()).thenReturn(outcome);
    return row;
  }

  @Test
  void theQueryIsAskedForExactlyTheOutcomesTheMapLists() {
    when(repository.findUnrecordedSince(any(), any())).thenReturn(List.of());

    new PaymentOrderQueryAdapter(repository).unrecordedPaymentsSince(cutoff);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> outcomes = ArgumentCaptor.forClass(Collection.class);
    verify(repository).findUnrecordedSince(outcomes.capture(), eq(cutoff));
    assertThat(outcomes.getValue())
        .containsExactlyInAnyOrderElementsOf(
            PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES.keySet().stream().map(Enum::name).toList())
        .containsExactlyInAnyOrder("RECORDING_FAILED", "DUPLICATE_REFERENCE", "AMOUNT_MISMATCH");
  }

  @Test
  void eachRowIsReportedWithTheReasonForItsOutcome() {
    UUID agreementId = UUID.randomUUID();
    UUID failed = UUID.randomUUID();
    UUID reused = UUID.randomUUID();
    UUID mismatched = UUID.randomUUID();
    List<UnrecordedAttempt> rows =
        List.of(
            row(failed, agreementId, "RECORDING_FAILED"),
            row(reused, agreementId, "DUPLICATE_REFERENCE"),
            row(mismatched, agreementId, "AMOUNT_MISMATCH"));
    when(repository.findUnrecordedSince(any(), eq(cutoff))).thenReturn(rows);

    assertThat(new PaymentOrderQueryAdapter(repository).unrecordedPaymentsSince(cutoff))
        .containsExactly(
            new UnrecordedPayment(failed, agreementId, Reason.NOT_RECORDED),
            new UnrecordedPayment(reused, agreementId, Reason.ID_REUSED),
            new UnrecordedPayment(mismatched, agreementId, Reason.AMOUNT_MISMATCH));
  }

  @Test
  void everyReasonHasAnOutcomeAndNoOtherOutcomeAlerts() {
    assertThat(PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES.values())
        .containsExactlyInAnyOrder(Reason.values());
    assertThat(PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES)
        .doesNotContainKeys(
            PaymentEventOutcome.ERRORED,
            PaymentEventOutcome.MISSING_REFERENCE,
            PaymentEventOutcome.UNKNOWN_ORDER,
            PaymentEventOutcome.CONFIRMED,
            PaymentEventOutcome.ALREADY_CONFIRMED);
  }

  @Test
  void theOrdersViewAddsErroredToTheAlertOutcomesAndTheAlertKeepsItsThree() {
    assertThat(PaymentOrderQueryAdapter.ATTENTION_OUTCOMES)
        .containsExactlyInAnyOrder(
            PaymentEventOutcome.RECORDING_FAILED,
            PaymentEventOutcome.DUPLICATE_REFERENCE,
            PaymentEventOutcome.AMOUNT_MISMATCH,
            PaymentEventOutcome.ERRORED)
        .containsAll(PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES.keySet());
    assertThat(PaymentOrderQueryAdapter.UNRECORDED_OUTCOMES).hasSize(3);
  }
}
