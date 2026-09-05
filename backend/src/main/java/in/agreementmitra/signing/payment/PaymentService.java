package in.agreementmitra.signing.payment;

import in.agreementmitra.ConflictException;
import in.agreementmitra.signing.PaymentConfirmation;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.PaymentStateResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Records payment confirmations and waivers through the vendor-neutral {@link PaymentConfirmation}
 * seam. The <b>only</b> implementations of that seam introduced by this change are a manual
 * staff-recorded confirmation and a waiver - no payment gateway is integrated, no card or UPI
 * credential is handled, and no refund is processed.
 *
 * <p>Both actions require the STAFF role, enforced in the security filter chain (before this
 * service is ever entered) so a refusal cannot act as an existence oracle for someone else's
 * agreement.
 *
 * <p>Payment state is server-managed throughout: it is never read from a request body on create or
 * any other request, and every transition records who caused it and when.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  /** The only currency the platform transacts in today. */
  private static final String DEFAULT_CURRENCY = "INR";

  private final AgreementService agreementService;

  PaymentService(AgreementService agreementService) {
    this.agreementService = agreementService;
  }

  /**
   * Record a manual payment confirmation: the agreement becomes {@code PAID}, and the amount,
   * reference, actor and time are recorded.
   *
   * <p>The external reference is normalised (trimmed + uppercased) so casing/spacing variants
   * collide on the unique index exactly as they should. The duplicate-reference 409 comes from
   * catching that index's violation, never a read-then-write pre-check - two concurrent
   * confirmations of one payment must not both succeed.
   *
   * @throws ConflictException {@code PAYMENT_REFERENCE_ALREADY_USED} if the reference is already
   *     recorded against some agreement
   */
  public PaymentStateResponse confirm(
      UUID staffIdentityId,
      UUID agreementId,
      BigDecimal amount,
      String currency,
      String reference) {
    PaymentConfirmation confirmation =
        new PaymentConfirmation(
            agreementId,
            amount,
            blankTo(currency, DEFAULT_CURRENCY).toUpperCase(Locale.ROOT),
            normalizeReference(reference),
            Instant.now());
    try {
      PaymentStateResponse response =
          agreementService.recordPayment(agreementId, confirmation, staffIdentityId);
      // Never log the amount or the reference - the fact is enough for an operations trail.
      log.debug("Payment recorded for agreement {}", agreementId);
      return response;
    } catch (DataIntegrityViolationException e) {
      throw ConflictException.paymentReferenceAlreadyUsed();
    }
  }

  /**
   * Waive payment for an agreement: a deliberate decision to proceed without money. Stored as a
   * state distinct from {@code PAID} forever, with no invented amount or reference, so "how much
   * money came in" and "may this proceed" stay separately answerable.
   */
  public PaymentStateResponse waive(UUID staffIdentityId, UUID agreementId) {
    PaymentStateResponse response = agreementService.waivePayment(agreementId, staffIdentityId);
    log.debug("Payment waived for agreement {}", agreementId);
    return response;
  }

  /** The agreement's payment state as read/reported. */
  public PaymentStateResponse view(UUID agreementId) {
    return agreementService.paymentView(agreementId);
  }

  private static String normalizeReference(String reference) {
    if (reference == null) {
      return null;
    }
    String trimmed = reference.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
