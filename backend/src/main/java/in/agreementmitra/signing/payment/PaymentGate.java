package in.agreementmitra.signing.payment;

import in.agreementmitra.ConflictException;
import in.agreementmitra.signing.PaymentGateMode;
import in.agreementmitra.signing.PaymentState;
import in.agreementmitra.signing.agreement.AgreementService;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The <b>single</b> payment precondition on the fulfilment pipeline (design D5/D6).
 *
 * <p>It is evaluated at exactly two steps, and both are chosen on purpose:
 *
 * <ol>
 *   <li><b>e-stamp intake</b> - where real money leaves. Staff buy an SHCIL certificate out of
 *       band; gating only at signing would let an unpaid order consume stamp duty.
 *   <li><b>eSign initiation</b> - where each signature is a billable vendor transaction.
 * </ol>
 *
 * <p>In {@link PaymentGateMode#OPTIONAL} (the default, and the only mode usable today) the gate
 * records payment state and permits progress whatever that state is. In {@link
 * PaymentGateMode#REQUIRED} it refuses unless the agreement is {@code PAID} or {@code WAIVED}. Both
 * modes are exercised by tests on every build, so {@code REQUIRED} does not rot while production
 * runs {@code OPTIONAL}.
 *
 * <p>The refusal is a <b>distinct</b> {@link ConflictException.Kind#PAYMENT_REQUIRED}, never folded
 * into missing-draft / missing-stamp / uncontactable-party: an operator staring at a 409 has to be
 * able to tell why the pipeline stopped, because a different person fixes each one.
 *
 * <p>Java-{@code public} so the {@code signingrequest} package can call it; still
 * Modulith-internal. No payment gateway, provider SDK, or card/UPI handling is introduced here.
 */
@Component
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentGate {

  private static final Logger log = LoggerFactory.getLogger(PaymentGate.class);

  private final PaymentProperties properties;
  private final AgreementService agreementService;

  PaymentGate(PaymentProperties properties, AgreementService agreementService) {
    this.properties = properties;
    this.agreementService = agreementService;
  }

  /**
   * Make the active mode observable without reading configuration files (spec: "Mode is
   * observable"). Logged once at startup; also served by the staff payment endpoint.
   */
  @PostConstruct
  void announceMode() {
    log.info("Payment gate mode: {}", mode());
  }

  /** The active gate mode. */
  public PaymentGateMode mode() {
    return properties.mode();
  }

  /**
   * Evaluate the gate for one agreement. Callers MUST invoke this <b>before any side effect</b> at
   * the gated step - no blob written, no provider called, no state transitioned, no vendor charge
   * incurred.
   *
   * <p>An agreement that cannot be found is permitted through: resolving it is the caller's job and
   * its own 404 is the honest answer, so the gate never turns a missing agreement into a payment
   * problem.
   *
   * @throws ConflictException {@code PAYMENT_REQUIRED} when the gate is {@code REQUIRED} and the
   *     agreement is neither {@code PAID} nor {@code WAIVED}
   */
  public void require(UUID agreementId) {
    if (mode() != PaymentGateMode.REQUIRED) {
      return; // OPTIONAL: state is recorded, progress is permitted regardless
    }
    PaymentState state = agreementService.paymentState(agreementId).orElse(null);
    if (state == null) {
      return; // unknown agreement - the caller's 404 is the honest refusal, not ours
    }
    if (!state.satisfiesGate()) {
      // No agreement id, no amount, no party data in the log - just the fact.
      log.debug("Payment gate refused a step: agreement is not paid");
      throw ConflictException.paymentRequired();
    }
  }
}
