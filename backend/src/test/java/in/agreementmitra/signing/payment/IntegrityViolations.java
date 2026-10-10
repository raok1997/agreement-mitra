package in.agreementmitra.signing.payment;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Hand-built refusals, shaped as Spring and Hibernate raise them, for the unit tests that decide
 * what a refused payment write was. Every one carries {@link #DATABASE_TEXT} in its messages, so a
 * test can prove that text goes no further.
 */
final class IntegrityViolations {

  /** Stands in for the SQL and failing-row text a real refusal's message holds. */
  static final String DATABASE_TEXT = "Key (provider_payment_id)=(pay_LEAKED) already exists";

  private IntegrityViolations() {}

  /** A unique violation (SQL state 23505) the database attributes to {@code constraintName}. */
  static DataIntegrityViolationException ofConstraint(String constraintName) {
    return ofConstraint(constraintName, "23505");
  }

  /** A check violation (SQL state 23514) the database attributes to {@code constraintName}. */
  static DataIntegrityViolationException ofCheckConstraint(String constraintName) {
    return ofConstraint(constraintName, "23514");
  }

  private static DataIntegrityViolationException ofConstraint(
      String constraintName, String sqlState) {
    return new DataIntegrityViolationException(
        "could not execute statement [" + DATABASE_TEXT + "]",
        new ConstraintViolationException(
            DATABASE_TEXT, new SQLException(DATABASE_TEXT, sqlState), constraintName));
  }

  /** A data error - a value too long for its column - which names no constraint. */
  static DataIntegrityViolationException valueTooLong() {
    return new DataIntegrityViolationException(
        "could not execute statement [" + DATABASE_TEXT + "]",
        new DataException(DATABASE_TEXT, new SQLException(DATABASE_TEXT, "22001")));
  }

  /** A refusal with nothing from Hibernate or the driver behind it. */
  static DataIntegrityViolationException bare() {
    return new DataIntegrityViolationException(DATABASE_TEXT);
  }
}
