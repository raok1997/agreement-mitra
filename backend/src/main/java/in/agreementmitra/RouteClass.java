package in.agreementmitra;

/**
 * The route classes the anonymous-surface rate limits are assigned by
 * (anonymous-surface-abuse-controls D4). Every API route is in exactly one; {@link RouteClassifier}
 * holds the table. Budgets and lockouts are held per class, so tripping one never refuses the same
 * source on another.
 */
enum RouteClass {
  /** Renders a PDF: bounded per source; render admission control bounds the rest. */
  RENDER,
  /**
   * The capture form's HTML live preview: compiles HTML and never calls Gotenberg, so it gets a
   * generous ceiling and no lockout -- slow typing on a phone fires one per keystroke.
   */
  LIVE_PREVIEW,
  /** Creates an agreement anonymously. */
  ANONYMOUS_WRITE,
  /** The login handshake and logout. */
  AUTH,
  /** Writes through an agreement's capability id: per source and per resource. */
  CAPABILITY_WRITE,
  /** Reads through an agreement's capability id: per source and per resource. */
  CAPABILITY_READ,
  /** Catalog, jurisdiction and CSRF bootstrap reads: a high ceiling, never a lockout. */
  BOOTSTRAP,
  /** Anything not listed, including the authenticated and staff routes. */
  DEFAULT,
  /** Not reachable from outside (Caddy proxies only {@code /api/*}): health and error. */
  NOT_LIMITED,
  /** Has a better control of its own: recovery (its own limiter) and the webhooks (HMAC). */
  EXCLUDED;

  /** Whether this class carries a configured limit. */
  boolean limited() {
    return this != NOT_LIMITED && this != EXCLUDED;
  }

  /** The label events and configuration use, e.g. {@code capability-read}. */
  String label() {
    return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }
}
