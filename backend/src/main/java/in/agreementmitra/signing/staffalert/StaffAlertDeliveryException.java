package in.agreementmitra.signing.staffalert;

/**
 * A staff alert the channel did not accept.
 *
 * <p><b>It never carries a cause</b>, and there is no constructor that takes one: the underlying
 * I/O exception's message embeds the request URL, and the webhook token is in that URL's path.
 */
final class StaffAlertDeliveryException extends RuntimeException {

  enum Kind {
    /** Worth another attempt: a timeout, a connection error, a rate limit, a server error. */
    TRANSIENT,
    /** Retrying cannot help: a redirect, a client error, an agreement that no longer resolves. */
    PERMANENT
  }

  /** {@link #status()} when no HTTP response was received. */
  static final int NO_STATUS = 0;

  private final Kind kind;
  private final int status;

  StaffAlertDeliveryException(Kind kind, int status) {
    super("Staff alert not delivered (" + kind + ", status " + status + ")");
    this.kind = kind;
    this.status = status;
  }

  Kind kind() {
    return kind;
  }

  int status() {
    return status;
  }
}
