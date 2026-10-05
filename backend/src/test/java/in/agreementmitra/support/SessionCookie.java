package in.agreementmitra.support;

import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/** Session-cookie helpers for integration tests (cookie-session-auth D9). */
public final class SessionCookie {

  public static final String NAME = "__Host-am_session";

  /** The secure-mode login-binding cookie (login-browser-binding D1). */
  public static final String LOGIN_BINDING_NAME = "__Host-am_login";

  /** The insecure-mode login-binding cookie, ignored in secure mode. */
  public static final String INSECURE_LOGIN_BINDING_NAME = "am_login";

  private SessionCookie() {}

  /** A {@code Cookie} header value carrying the given session value. */
  public static String header(String value) {
    return NAME + "=" + value;
  }

  /** A {@code Cookie} header value carrying the given secure-mode login-binding nonce. */
  public static String loginBindingHeader(String nonce) {
    return LOGIN_BINDING_NAME + "=" + nonce;
  }

  /** Every {@code Set-Cookie} header for {@code name}, in response order. */
  public static List<String> setCookies(HttpHeaders headers, String name) {
    List<String> all = headers.get(HttpHeaders.SET_COOKIE);
    return all == null ? List.of() : all.stream().filter(h -> h.startsWith(name + "=")).toList();
  }

  /** The LAST {@code Set-Cookie} header for {@code name} -- the one the browser keeps. */
  public static Optional<String> lastSetCookie(HttpHeaders headers, String name) {
    List<String> matching = setCookies(headers, name);
    return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(matching.size() - 1));
  }

  /** The value of the last {@code Set-Cookie} for {@code name}. */
  public static Optional<String> lastValue(HttpHeaders headers, String name) {
    return lastSetCookie(headers, name)
        .map(h -> h.substring(name.length() + 1))
        .map(v -> v.contains(";") ? v.substring(0, v.indexOf(';')) : v);
  }
}
