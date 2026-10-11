package in.agreementmitra.signing.payment;

import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SurplusPayment;
import in.agreementmitra.signing.UnrecordedPayment;
import in.agreementmitra.signing.UnrecordedPayment.Reason;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapts the package-private {@link PaymentOrderRepository} to the module-root {@link
 * PaymentOrderQuery} seam, the same shape as the signing-request query adapter.
 */
@Component
class PaymentOrderQueryAdapter implements PaymentOrderQuery {

  /**
   * The ledger outcomes that mean "captured, and not recorded against the order", and what each is
   * reported as. <b>The only place they are listed</b>: the keys bind the look-back query and the
   * values map its rows, so the two cannot disagree.
   */
  static final Map<PaymentEventOutcome, Reason> UNRECORDED_OUTCOMES =
      Map.of(
          PaymentEventOutcome.RECORDING_FAILED, Reason.NOT_RECORDED,
          PaymentEventOutcome.DUPLICATE_REFERENCE, Reason.ID_REUSED,
          PaymentEventOutcome.AMOUNT_MISMATCH, Reason.AMOUNT_MISMATCH);

  private final PaymentOrderRepository repository;

  PaymentOrderQueryAdapter(PaymentOrderRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean existsForAgreement(UUID agreementId) {
    return repository.countByAgreementId(agreementId) > 0;
  }

  @Override
  public List<UUID> agreementsWithOrderPaidSince(Instant cutoff) {
    return repository.findAgreementIdsPaidSince(cutoff);
  }

  @Override
  public List<SurplusPayment> surplusOrdersPaidSince(Instant cutoff) {
    return repository.findSurplusPaidSince(cutoff);
  }

  @Override
  public List<UnrecordedPayment> unrecordedPaymentsSince(Instant cutoff) {
    List<String> outcomes = UNRECORDED_OUTCOMES.keySet().stream().map(Enum::name).toList();
    return repository.findUnrecordedSince(outcomes, cutoff).stream()
        .map(
            row ->
                new UnrecordedPayment(
                    row.getPaymentOrderId(),
                    row.getAgreementId(),
                    UNRECORDED_OUTCOMES.get(PaymentEventOutcome.valueOf(row.getOutcome()))))
        .toList();
  }
}
