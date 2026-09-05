package in.agreementmitra.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Satisfies the payment gate for a test whose subject is <b>not</b> payment.
 *
 * <p>The gate ships {@code REQUIRED}, and the test profile matches production rather than pinning
 * itself to {@code OPTIONAL} - otherwise the configuration we actually deploy would be the one
 * configuration the suite never exercises. The consequence is that every stamping and signing
 * fixture now has to get past the gate first.
 *
 * <p>This helper is how they do it, and it is deliberately <b>not</b> a way to skip the gate: the
 * agreement really is moved to a state that satisfies it, so the gate is genuinely evaluated and
 * genuinely passed on every one of those tests. If the gate ever stopped being enforced, or started
 * refusing a paid agreement, those tests would still notice.
 *
 * <p><b>Why a direct write.</b> These are fixtures, not assertions. Driving the STAFF confirm/waive
 * endpoints from every stamping test would need a staff session in each one and would turn twenty
 * tests about certificates and signatures into twenty tests about payment. The confirmation seam
 * itself is exercised where it belongs - the manual STAFF path in {@code
 * PaymentGateIntegrationTest}, the gateway path in the Razorpay tests - and both of those go
 * through the real API.
 *
 * <p>{@link #waive} is the default choice: "this test proceeds deliberately without money" is
 * exactly what {@code WAIVED} means, and it invents no amount or payment reference that a report
 * would later have to explain.
 */
public final class Payments {

  private Payments() {}

  /**
   * Mark the agreement {@code WAIVED} - the state a STAFF member would set to let one agreement
   * through without money. Carries no amount and no external reference, exactly as the real waiver
   * does, so nothing in the books can mistake it for a payment.
   */
  public static void waive(JdbcTemplate jdbc, UUID agreementId) {
    jdbc.update(
        "UPDATE agreement SET payment_state = 'WAIVED', payment_amount = NULL,"
            + " payment_currency = NULL, payment_reference = NULL, payment_recorded_at = ?"
            + " WHERE id = ?",
        Timestamp.from(Instant.now()),
        agreementId);
  }

  /**
   * Mark the agreement {@code PAID} with a unique reference. Use only where a test needs the
   * distinction from a waiver; {@link #waive} is otherwise preferred, because it invents nothing.
   */
  public static void markPaid(JdbcTemplate jdbc, UUID agreementId, String reference) {
    jdbc.update(
        "UPDATE agreement SET payment_state = 'PAID', payment_amount = 499.00,"
            + " payment_currency = 'INR', payment_reference = ?, payment_recorded_at = ?"
            + " WHERE id = ?",
        reference,
        Timestamp.from(Instant.now()),
        agreementId);
  }

  /** Return the agreement to {@code UNPAID}, for a test that needs the gate to actually refuse. */
  public static void reset(JdbcTemplate jdbc, UUID agreementId) {
    jdbc.update(
        "UPDATE agreement SET payment_state = 'UNPAID', payment_amount = NULL,"
            + " payment_currency = NULL, payment_reference = NULL, payment_recorded_at = NULL"
            + " WHERE id = ?",
        agreementId);
  }
}
