package in.agreementmitra.signing;

/**
 * A send that did not happen, classified so the caller knows whether waiting will help.
 *
 * <p><b>Transient</b> (connection refused, timeout, provider unavailable, rate limited) is retried
 * with bounded backoff. <b>Permanent</b> (a rejected or non-existent address, a message the
 * provider refuses outright) is not retried; it is recorded with its reason and surfaced for staff,
 * because retrying a mailbox that does not exist only delays the moment a human looks at it.
 *
 * <p>{@link #reason()} is a short, fixed token intended for storage and staff display. It is never
 * a provider message and never carries the recipient address or any document content.
 */
public class EmailDeliveryException extends RuntimeException {

  private final boolean permanent;
  private final String reason;

  private EmailDeliveryException(boolean permanent, String reason, Throwable cause) {
    // The message is for redacted server-side logging only; it MUST NOT reach a client body.
    super(reason, cause);
    this.permanent = permanent;
    this.reason = reason;
  }

  /**
   * Retrying may succeed: a timeout, a refused connection, an unavailable or throttled provider.
   */
  public static EmailDeliveryException transientFailure(String reason, Throwable cause) {
    return new EmailDeliveryException(false, reason, cause);
  }

  /** Retrying cannot succeed: the address was rejected, or the message itself was refused. */
  public static EmailDeliveryException permanentFailure(String reason, Throwable cause) {
    return new EmailDeliveryException(true, reason, cause);
  }

  public boolean permanent() {
    return permanent;
  }

  /** A short, fixed failure token safe to persist and show to staff. */
  public String reason() {
    return reason;
  }
}
