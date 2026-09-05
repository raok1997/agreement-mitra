package in.agreementmitra.signing.payment;

/**
 * The lifecycle of one gateway order, in <b>our</b> vocabulary rather than the vendor's. Razorpay
 * moves an order {@code created -> attempted -> paid}; {@code attempted} means a customer opened
 * checkout and did not complete, which is still outstanding as far as we are concerned, so it maps
 * to {@link #CREATED}.
 *
 * <p>Keeping our own values means a second provider is an adapter rather than a migration, and a
 * vendor status we have never seen can never drive a terminal transition by accident.
 */
enum PaymentOrderStatus {

  /** Outstanding: placed with the provider, not yet paid. Reused when the customer reloads. */
  CREATED,

  /** Paid and confirmed authoritatively (verified webhook, or an authoritative order read). */
  PAID,

  /** The provider reported the order failed. A new order may be created. */
  FAILED,

  /** Outstanding past its useful life and abandoned. A new order may be created. */
  EXPIRED;

  /** Whether this order is still the one a returning customer should be sent back to. */
  boolean outstanding() {
    return this == CREATED;
  }

  /** Whether a confirmation has already been applied - the idempotency check. */
  boolean settled() {
    return this == PAID;
  }
}
