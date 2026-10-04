package in.agreementmitra;

/**
 * The caller a per-source control is keyed on, as resolved by {@link ClientSourceResolver}.
 *
 * @param key the bucket key: an IPv4 address, an IPv6 network at the configured prefix, or {@link
 *     #UNRESOLVED} -- never a raw header value
 * @param redacted the form a log may carry: IPv4 truncated to {@code /24}, IPv6 to {@code /48}
 */
public record ClientSource(String key, String redacted) {

  /**
   * The shared bucket for a request whose address could not be parsed. Behind the trusted proxy
   * that only happens when the proxy itself sent a bad value, and then the immediate peer IS the
   * proxy -- so this is the "immediate peer" bucket, shared and limited rather than unlimited.
   */
  public static final String UNRESOLVED = "unresolved";

  static ClientSource unresolved() {
    return new ClientSource(UNRESOLVED, UNRESOLVED);
  }
}
