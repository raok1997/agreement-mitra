package in.agreementmitra.signing;

import java.util.UUID;

/**
 * Read-only seam over payment orders, exposed at the module root (like {@link SigningRequestQuery})
 * so the agreement sub-package can ask "has an order been created for this agreement?" without
 * depending on the {@code payment} package internals. Backs the unpaid-draft deletability rule.
 */
public interface PaymentOrderQuery {

  /** True if any payment order, in any status, has been created for the given agreement. */
  boolean existsForAgreement(UUID agreementId);
}
