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
}
