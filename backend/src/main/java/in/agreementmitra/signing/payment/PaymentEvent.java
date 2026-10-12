package in.agreementmitra.signing.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * One payment confirmation attempt, as the ledger stores it (payment-event-ledger D6, D9). Not a
 * JPA entity: one statement writes the table, and its readers are native queries - the staff alert
 * look-back and the staff orders view in {@link PaymentOrderRepository}, and {@link
 * PaymentEventLedger#attemptsFor}.
 *
 * <p>{@link #of} makes every string storable before the write, so a gateway value the payment
 * record could not hold does not also lose the ledger row, and computes {@link #attemptKey} from
 * the values it is about to store, so the key and the row cannot disagree.
 *
 * <p>The key covers the nine values that make an attempt distinct. The received time and the
 * presented event id are outside it: a repeat differing only in those raises the row's count.
 */
record PaymentEvent(
    String attemptKey,
    String source,
    String presentedEventId,
    String eventType,
    String providerOrderId,
    String providerPaymentId,
    long amountMinorUnits,
    String currency,
    String outcome,
    String refusedRule,
    String sqlState,
    Instant receivedAt) {

  /** Column widths of {@code payment_event} (V29). */
  static final int SOURCE_WIDTH = 24;

  static final int EVENT_ID_WIDTH = 64;
  static final int EVENT_TYPE_WIDTH = 32;
  static final int PROVIDER_ID_WIDTH = 255;
  static final int CURRENCY_WIDTH = 16;
  static final int OUTCOME_WIDTH = 24;
  static final int RULE_WIDTH = 128;
  static final int SQL_STATE_WIDTH = 8;

  /** Joins the key values. Removed from every stored value, so it cannot occur inside one. */
  private static final char SEPARATOR = '\u001F';

  /** Stands for {@code null} in the key. Postgres refuses it in text, so no value holds it. */
  private static final char NUL = '\u0000';

  static PaymentEvent of(
      PaymentEventOrigin origin,
      String providerOrderId,
      String providerPaymentId,
      long amountMinorUnits,
      String currency,
      Instant receivedAt,
      PaymentEventOutcome outcome,
      String refusedRule,
      String sqlState) {
    String source = origin.source().name();
    String eventType = storable(origin.eventType(), EVENT_TYPE_WIDTH);
    String orderId = storable(providerOrderId == null ? "" : providerOrderId, PROVIDER_ID_WIDTH);
    String paymentId = storable(providerPaymentId, PROVIDER_ID_WIDTH);
    String storedCurrency = storable(currency, CURRENCY_WIDTH);
    String outcomeName = outcome.name();
    String rule = storable(refusedRule, RULE_WIDTH);
    String state = storable(sqlState, SQL_STATE_WIDTH);
    return new PaymentEvent(
        attemptKey(
            orderId,
            source,
            eventType,
            outcomeName,
            paymentId,
            Long.toString(amountMinorUnits),
            storedCurrency,
            rule,
            state),
        source,
        storable(origin.presentedEventId(), EVENT_ID_WIDTH),
        eventType,
        orderId,
        paymentId,
        amountMinorUnits,
        storedCurrency,
        outcomeName,
        rule,
        state,
        receivedAt);
  }

  /**
   * {@code value} without the two characters a stored value may not hold, cut to {@code width}, and
   * not ending on the first half of a surrogate pair the cut split.
   */
  static String storable(String value, int width) {
    if (value == null) {
      return null;
    }
    StringBuilder kept = new StringBuilder(Math.min(value.length(), width));
    for (int i = 0; i < value.length() && kept.length() < width; i++) {
      char c = value.charAt(i);
      if (c != NUL && c != SEPARATOR) {
        kept.append(c);
      }
    }
    int last = kept.length() - 1;
    if (last >= 0 && Character.isHighSurrogate(kept.charAt(last))) {
      kept.setLength(last);
    }
    return kept.toString();
  }

  /**
   * Lower-case SHA-256 hex of the values joined by {@link #SEPARATOR}, UTF-8. The encoding is
   * fixed: changing it gives an identical attempt a second row from then on.
   */
  private static String attemptKey(String... values) {
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        joined.append(SEPARATOR);
      }
      if (values[i] == null) {
        joined.append(NUL);
      } else {
        joined.append(values[i]);
      }
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", impossible);
    }
  }
}
