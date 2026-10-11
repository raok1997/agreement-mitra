package in.agreementmitra.signing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only seam over payment orders, exposed at the module root (like {@link SigningRequestQuery})
 * so other sub-packages can ask about payment orders without depending on the {@code payment}
 * package internals. Backs the unpaid-draft deletability rule and the staff alert look-backs.
 */
public interface PaymentOrderQuery {

  /** True if any payment order, in any status, has been created for the given agreement. */
  boolean existsForAgreement(UUID agreementId);

  /**
   * The distinct agreements with a payment order the gateway confirmed paid at or after {@code
   * cutoff}, <b>surplus orders excluded</b> - those are reported by {@link #surplusOrdersPaidSince}
   * instead, never by both. The time compared is our own confirmation time, not the gateway's
   * capture time, so a payment reconciled late is still found.
   */
  List<UUID> agreementsWithOrderPaidSince(Instant cutoff);

  /**
   * The surplus payment orders confirmed paid at or after {@code cutoff}: payments that were
   * captured on an agreement which already held one.
   */
  List<SurplusPayment> surplusOrdersPaidSince(Instant cutoff);

  /**
   * The payment orders with a confirmation attempt, last received at or after {@code cutoff}, that
   * ended as a recording failure, a refused duplicate reference or an amount mismatch - one entry
   * for each order and reason, however many attempts ended that way.
   *
   * <p>The time compared is when the attempt was <b>last</b> received, so an order that is still
   * failing stays in the result. An order recorded as paid is excluded, whatever the agreement's
   * own payment state; an expired or failed order is not. An attempt for an order we do not hold is
   * excluded, and so is an attempt that carried no gateway payment id - an event without payment
   * detail, not a captured payment.
   */
  List<UnrecordedPayment> unrecordedPaymentsSince(Instant cutoff);
}
