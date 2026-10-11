package in.agreementmitra.signing.payment;

import java.util.regex.Pattern;

/**
 * Where a payment confirmation attempt came from (payment-event-ledger D4). The confirmation path
 * cannot know who called it, so each caller says.
 *
 * <p><b>{@code presentedEventId} is not authenticated</b> (D5). It comes from a request header the
 * webhook signature does not cover, so anyone replaying a validly signed body can present any
 * value. It is a label for the ledger: nothing may branch on it.
 *
 * @param source which of the three confirmation callers this is
 * @param presentedEventId the event id presented with a webhook, or null
 * @param eventType the webhook's event type, from the signed body; null for the other sources
 */
record PaymentEventOrigin(Source source, String presentedEventId, String eventType) {

  enum Source {
    WEBHOOK,
    BROWSER_CALLBACK,
    RECONCILIATION
  }

  /** A short plain token. Anything else presented as an event id is recorded as absent. */
  private static final Pattern PLAIN_TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  static PaymentEventOrigin webhook(String presentedEventId, String eventType) {
    boolean plain = presentedEventId != null && PLAIN_TOKEN.matcher(presentedEventId).matches();
    return new PaymentEventOrigin(Source.WEBHOOK, plain ? presentedEventId : null, eventType);
  }

  static PaymentEventOrigin browserCallback() {
    return new PaymentEventOrigin(Source.BROWSER_CALLBACK, null, null);
  }

  static PaymentEventOrigin reconciliation() {
    return new PaymentEventOrigin(Source.RECONCILIATION, null, null);
  }
}
