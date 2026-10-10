package in.agreementmitra.signing.payment;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Decides what a refused payment write was, by <b>which rule</b> refused it.
 *
 * <p>A {@code DataIntegrityViolationException} is not evidence of a duplicate: Spring raises it for
 * a value too long for its column, a numeric overflow, a check or not-null constraint, and for any
 * constraint a later change adds. Only the two uniqueness rules named here mean "this payment is
 * already recorded". Everything else - including a refusal that names no rule at all - is a failure
 * to record a payment, and must never be acknowledged as a duplicate.
 *
 * <p>The names are the index names from the migrations ({@code V16}, {@code V28}). An applied
 * migration is never edited, so the two copies cannot be merged; each is pinned by an integration
 * test against real Postgres, so renaming an index fails a test instead of silently turning
 * duplicates into failures.
 */
final class PaymentIntegrity {

  /** One payment reference is recorded against at most one agreement. */
  static final String AGREEMENT_PAYMENT_REFERENCE = "uq_agreement_payment_reference";

  /** One gateway payment id is held by at most one payment order. */
  static final String ORDER_PROVIDER_PAYMENT_ID = "uq_payment_order_provider_payment_id";

  /** The rules whose refusal makes a <b>gateway</b> confirmation a duplicate reference. */
  static final Set<String> GATEWAY_DUPLICATE_RULES =
      Set.of(AGREEMENT_PAYMENT_REFERENCE, ORDER_PROVIDER_PAYMENT_ID);

  private PaymentIntegrity() {}

  /**
   * The name of the rule the database reports as violated, lower-cased; empty when it reports none.
   * Read from Hibernate's exception rather than the driver's, which main code cannot compile
   * against. For a not-null violation the reported name is a column, not a constraint.
   */
  static Optional<String> violatedConstraint(DataIntegrityViolationException refused) {
    for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        String name = violation.getConstraintName();
        return name == null || name.isBlank()
            ? Optional.empty()
            : Optional.of(name.toLowerCase(Locale.ROOT));
      }
    }
    return Optional.empty();
  }

  /** The first SQL state in the cause chain, or {@code null} when there is none. */
  static String sqlState(DataIntegrityViolationException refused) {
    for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  /** Whether one of {@code rules} is what refused the write. */
  static boolean refusedBy(Set<String> rules, DataIntegrityViolationException refused) {
    return violatedConstraint(refused).filter(rules::contains).isPresent();
  }

  /**
   * The failure to raise for a refusal that is not a duplicate. Carries the reported rule and SQL
   * state and nothing else from {@code refused} - not its message, and not the exception itself.
   */
  static PaymentRecordingFailedException failure(DataIntegrityViolationException refused) {
    return new PaymentRecordingFailedException(
        violatedConstraint(refused).orElse(null), sqlState(refused));
  }

  /**
   * As {@link #failure}, having written the one ERROR line for it on the caller's logger. The
   * refusal is never handed to the logger: its message is the SQL and the failing row.
   *
   * @param subject what could not be paid for, already redacted - e.g. {@code order ****9f3k}
   */
  static PaymentRecordingFailedException loggedFailure(
      Logger log, String subject, DataIntegrityViolationException refused) {
    PaymentRecordingFailedException failure = failure(refused);
    log.error(
        "Payment recording failed for {}: the database refused the write (rule {}, SQL state {})",
        subject,
        failure.refusedRule(),
        failure.sqlState());
    return failure;
  }
}
