package in.agreementmitra.signing.payment;

/**
 * The database refused to record a payment, for a reason that is not a duplicate reference. Nothing
 * was recorded.
 *
 * <p><b>Built without a cause, on purpose.</b> The refusal's own exception carries the SQL
 * statement and, depending on the driver's configuration, the failing row. This one escapes to the
 * container, which logs its message and stack trace - so it holds only the name of the refused rule
 * and the SQL state, and no payment id, amount or database text.
 */
final class PaymentRecordingFailedException extends RuntimeException {

  /** What is reported when the database names no rule (a data error has none). */
  static final String UNNAMED = "unnamed";

  /** What is reported when the refusal carries no SQL state. */
  static final String UNKNOWN_STATE = "unknown";

  private final String refusedRule;
  private final String sqlState;

  /**
   * @param refusedRule the rule the database reported as violated, or {@code null} for none
   * @param sqlState the SQL state of the refusal, or {@code null} when unknown
   */
  PaymentRecordingFailedException(String refusedRule, String sqlState) {
    super(
        "payment could not be recorded: the database refused the write (rule "
            + orDefault(refusedRule, UNNAMED)
            + ", SQL state "
            + orDefault(sqlState, UNKNOWN_STATE)
            + ")");
    this.refusedRule = orDefault(refusedRule, UNNAMED);
    this.sqlState = orDefault(sqlState, UNKNOWN_STATE);
  }

  /** The refused rule's name, or {@link #UNNAMED}. */
  String refusedRule() {
    return refusedRule;
  }

  /** The SQL state, or {@link #UNKNOWN_STATE}. */
  String sqlState() {
    return sqlState;
  }

  private static String orDefault(String value, String fallback) {
    return value == null ? fallback : value;
  }
}
