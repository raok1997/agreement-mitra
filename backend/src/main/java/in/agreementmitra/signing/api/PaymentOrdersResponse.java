package in.agreementmitra.signing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The staff orders view: payment orders in two groups, an order in at most one.
 *
 * <p>What it withholds is structural. No record here has a field for the agreement id (a bearer
 * credential), the order's receipt (which is the agreement id), the gateway order id, or the
 * agreement's recorded payment reference, so none of them can be serialised.
 *
 * @param stuck orders holding a captured payment that was not recorded, newest attempt first
 * @param notPaid recent orders that were simply not paid, newest first
 */
public record PaymentOrdersResponse(Group stuck, Group notPaid) {

  /**
   * @param truncated whether entries beyond the cap were left out
   */
  public record Group(List<Entry> entries, boolean truncated) {}

  /**
   * One payment order.
   *
   * @param status a payment order status name
   * @param stuckOutcomes the ledger outcome names that make the order stuck, once each; empty for a
   *     not-paid entry
   * @param heldBy tracking references of the agreements already holding a payment id this order was
   *     refused for; empty unless an attempt ended {@code DUPLICATE_REFERENCE}
   * @param attempts every ledger attempt for the order, oldest first
   */
  public record Entry(
      String trackingReference,
      UUID paymentOrderId,
      String status,
      long amountMinorUnits,
      String currency,
      Instant createdAt,
      AgreementPayment agreementPayment,
      List<String> stuckOutcomes,
      List<String> heldBy,
      List<Attempt> attempts) {}

  /** How the order's agreement stands on payment, relative to this order's own payment. */
  public enum AgreementPayment {
    UNPAID,
    WAIVED,
    PAID_THIS_PAYMENT,
    PAID_OTHER_PAYMENT
  }

  /**
   * One ledger attempt.
   *
   * @param outcome the stored ledger outcome, passed through even when this build does not know it
   * @param providerPaymentId the gateway payment id; staff record a payment by hand under it
   * @param refusedRule our database's constraint name, where one refused the write
   * @param attemptCount how many times this same attempt was received
   */
  public record Attempt(
      String source,
      String outcome,
      String providerPaymentId,
      long amountMinorUnits,
      String currency,
      String refusedRule,
      String sqlState,
      Instant firstReceivedAt,
      Instant lastReceivedAt,
      long attemptCount) {

    /** Without the gateway payment id and the amount. */
    @Override
    public String toString() {
      return "Attempt{source=" + source + ", outcome=" + outcome + "}";
    }
  }
}
