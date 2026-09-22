package in.agreementmitra.signing.agreement;

import in.agreementmitra.ConflictException;
import in.agreementmitra.signing.PaymentState;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The <b>jurisdiction precondition</b> on paid fulfilment: may this agreement reach a step that
 * commits us to something real?
 *
 * <p>Since {@code state-stamp-duty-quoting} the answer comes from the <b>stamp duty rules</b>, not
 * a configured allowlist: an agreement is eligible when its duty jurisdiction has a rule in effect
 * that may be charged (a current counsel review, or {@code rules.stamp-duty.allow-unreviewed}) and
 * the calculator quotes it with at least one plannable stamp option. There is exactly one source of
 * eligibility truth -- the rules -- so admitting a state means seeding and reviewing its rules.
 *
 * <p>It is evaluated at <b>four</b> steps - every point that commits us in a jurisdiction:
 *
 * <ol>
 *   <li><b>finalise</b> - places the order and puts the agreement in the staff stamp queue.
 *   <li><b>checkout</b> - where the customer's money moves.
 *   <li><b>e-stamp intake</b> - where a real certificate is bought.
 *   <li><b>eSign initiation</b> - where a billable vendor transaction is incurred.
 * </ol>
 *
 * <p>The first two use {@link #require} and judge the agreement's <b>current</b> terms. The last
 * two use {@link #requireForFulfilment}: an agreement already <b>paid</b> with a frozen stamp quote
 * is judged by that quote, so a rule edit after payment cannot strand a paid order. A waived or
 * unquoted agreement is still judged afresh, because a staff waiver satisfies the payment gate but
 * proves nothing about fulfilment.
 *
 * <p>The refusal is a <b>distinct</b> {@link ConflictException.Kind#JURISDICTION_UNSUPPORTED},
 * never folded into payment-required: an operator staring at a 409 must be able to tell why the
 * pipeline stopped, because a different person fixes each one.
 *
 * <p>Java-{@code public} so the {@code payment}, {@code signingrequest} and stamp packages can call
 * it; still Modulith-internal.
 */
@Component
public class JurisdictionEligibility {

  private static final Logger log = LoggerFactory.getLogger(JurisdictionEligibility.class);

  private final AgreementRepository repository;
  private final StampQuoting quoting;
  private final StampQuoteRecordRepository frozenQuotes;

  JurisdictionEligibility(
      AgreementRepository repository,
      StampQuoting quoting,
      StampQuoteRecordRepository frozenQuotes) {
    this.repository = repository;
    this.quoting = quoting;
    this.frozenQuotes = frozenQuotes;
  }

  /** What the running instance may charge in, without reading rule files. */
  @PostConstruct
  void announceEligible() {
    log.info("Jurisdictions eligible for paid fulfilment (chargeable duty rules): {}", eligible());
  }

  /** The duty states admitted for paid fulfilment. Public by construction, never PII. */
  public Set<String> eligible() {
    return quoting.chargeableStates();
  }

  /**
   * Evaluate the precondition against the agreement's current terms (finalise, checkout). Callers
   * MUST invoke this <b>before any side effect</b> at the gated step.
   *
   * <p>An agreement that cannot be found is permitted through, exactly as {@code PaymentGate} does:
   * resolving it is the caller's job and its own 404 is the honest answer.
   *
   * @throws ConflictException {@code JURISDICTION_UNSUPPORTED} when the agreement has no duty
   *     jurisdiction, no chargeable rule, or no plannable stamp option
   */
  public void require(UUID agreementId) {
    Optional<StampQuoting.Evaluation> evaluation = quoting.evaluate(agreementId);
    if (evaluation.isEmpty()) {
      return; // Not our 404 to raise.
    }
    refuseUnlessPayable(evaluation.get());
  }

  /**
   * Evaluate the precondition at a fulfilment step (e-stamp intake, eSign initiation). A paid
   * agreement with a frozen stamp quote passes on that quote; anything else is judged afresh.
   */
  public void requireForFulfilment(UUID agreementId) {
    Agreement agreement = repository.findById(agreementId).orElse(null);
    if (agreement == null) {
      return;
    }
    if (agreement.paymentState() == PaymentState.PAID
        && frozenQuotes.findTopByAgreementIdOrderByCreatedAtDesc(agreementId).isPresent()) {
      return;
    }
    refuseUnlessPayable(quoting.evaluate(agreement));
  }

  private void refuseUnlessPayable(StampQuoting.Evaluation evaluation) {
    if (evaluation.payable()) {
      return;
    }
    // Facts only, no agreement id and no party data - the PaymentGate logging precedent.
    log.info(
        "Refused paid fulfilment: jurisdiction {} is not eligible ({})",
        evaluation.state(),
        evaluation.status());
    throw ConflictException.jurisdictionUnsupported(evaluation.state(), eligible());
  }
}
