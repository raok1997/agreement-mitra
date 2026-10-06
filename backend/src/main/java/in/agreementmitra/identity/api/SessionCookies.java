package in.agreementmitra.identity.api;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.identity.session.SessionService.SessionIssued;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * The single owner of the browser-session cookies (cookie-session-auth D6): the HttpOnly session
 * cookie, the rotation of the CSRF cookie that protects it, and the short-lived login-binding
 * cookie that ties a login to the browser that started it (login-browser-binding D1/D4).
 *
 * <p>{@code __Host-am_session} ({@code HttpOnly; Secure; SameSite=Lax; Path=/}, no {@code Domain})
 * in secure mode; {@code am_session} without {@code Secure} when {@code auth.cookie.secure=false}.
 * {@code Max-Age} comes from the session row's own expiry, so there is one source of truth. The
 * cookie value is never logged.
 *
 * <p>{@code __Host-am_login} / {@code am_login} follows the same mode switch and attributes. Its
 * {@code Max-Age} is the login-state TTL plus the handoff TTL plus a {@value
 * #LOGIN_BINDING_MARGIN_SECONDS} s margin, because the exchange reads it after both have run. Each
 * mode reads only its own name: in secure mode a presented {@code am_login} is ignored, since only
 * the {@code __Host-} name cannot be planted by a sibling host or over plain http.
 *
 * <p>Package-private: every caller (the exchange/logout controller, the session filter, and the
 * future OTP verify controller) lives in this package, so cookie minting is not exported on the
 * module's named interface.
 */
class SessionCookies {

  private static final Logger log = LoggerFactory.getLogger(SessionCookies.class);

  static final String SECURE_SESSION_COOKIE = "__Host-am_session";
  static final String INSECURE_SESSION_COOKIE = "am_session";
  static final String SECURE_LOGIN_BINDING_COOKIE = "__Host-am_login";
  static final String INSECURE_LOGIN_BINDING_COOKIE = "am_login";
  static final long LOGIN_BINDING_MARGIN_SECONDS = 60;
  static final String SECURE_CSRF_COOKIE = "__Host-XSRF-TOKEN";
  static final String INSECURE_CSRF_COOKIE = "XSRF-TOKEN";
  static final String CSRF_HEADER = "X-XSRF-TOKEN";
  static final String SAME_SITE = "Lax";
  static final String PATH = "/";

  private final boolean secure;
  private final long loginBindingMaxAgeSeconds;
  private final SessionService sessionService;
  private final CsrfTokenRepository csrfTokenRepository;

  SessionCookies(
      AuthProperties properties,
      SessionService sessionService,
      CsrfTokenRepository csrfTokenRepository) {
    this.secure = properties.cookie().secure();
    this.loginBindingMaxAgeSeconds =
        properties.loginStateTtl().plus(properties.handoffTtl()).toSeconds()
            + LOGIN_BINDING_MARGIN_SECONDS;
    this.sessionService = sessionService;
    this.csrfTokenRepository = csrfTokenRepository;
  }

  String sessionCookieName() {
    return secure ? SECURE_SESSION_COOKIE : INSECURE_SESSION_COOKIE;
  }

  String loginBindingCookieName() {
    return secure ? SECURE_LOGIN_BINDING_COOKIE : INSECURE_LOGIN_BINDING_COOKIE;
  }

  /**
   * Establish a freshly-minted session in the browser. Called only after the mint succeeded. Order
   * is load-bearing: set the cookie, rotate CSRF, and only then revoke any different prior session
   * the browser carried -- so a failure in the revoke cannot cost the user the new login.
   */
  void establish(HttpServletRequest request, HttpServletResponse response, SessionIssued issued) {
    Optional<String> prior = read(request);
    long maxAge = Math.max(0, Duration.between(Instant.now(), issued.expiresAt()).toSeconds());
    writeCookie(response, sessionCookieName(), issued.value(), maxAge);
    rotateCsrf(request, response);
    prior
        .filter(value -> !value.equals(issued.value()))
        .ifPresent(
            value -> {
              try {
                sessionService.revoke(value);
              } catch (RuntimeException e) {
                // The new login stands; the prior row is unreachable once its cookie is overwritten
                // and expires at its TTL. Never log the value.
                log.warn(
                    "Revoking the prior session at login failed: {}", e.getClass().getSimpleName());
              }
            });
  }

  /**
   * Expire the session cookie and the login-binding cookie (same names, paths and attributes) and
   * rotate CSRF. Never revokes. Clearing the binding means a handoff the previous user of a shared
   * browser never exchanged cannot be completed after logout.
   */
  void clear(HttpServletRequest request, HttpServletResponse response) {
    writeCookie(response, sessionCookieName(), "", 0);
    clearLoginBinding(response);
    rotateCsrf(request, response);
  }

  /** The configured session cookie's value, if present and non-blank. */
  Optional<String> read(HttpServletRequest request) {
    return readCookie(request, sessionCookieName());
  }

  /** Set the login-binding cookie carrying the raw nonce minted at {@code /google/start}. */
  void setLoginBinding(HttpServletResponse response, String nonce) {
    writeCookie(response, loginBindingCookieName(), nonce, loginBindingMaxAgeSeconds);
  }

  /**
   * The configured mode's login-binding cookie value, or {@code null} when absent or blank -- the
   * services refuse a blank nonce themselves. Never falls back to the other mode's name.
   */
  String readLoginBinding(HttpServletRequest request) {
    return readCookie(request, loginBindingCookieName()).orElse(null);
  }

  /**
   * Expire the login-binding cookie with the same name, path and {@code Secure} it was set with.
   */
  void clearLoginBinding(HttpServletResponse response) {
    writeCookie(response, loginBindingCookieName(), "", 0);
  }

  private static Optional<String> readCookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return Optional.empty();
    }
    return Arrays.stream(cookies)
        .filter(cookie -> name.equals(cookie.getName()))
        .map(Cookie::getValue)
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  /**
   * The one cookie writer, so a deletion always carries the name, path and Secure it was set with.
   */
  private void writeCookie(
      HttpServletResponse response, String name, String value, long maxAgeSeconds) {
    ResponseCookie cookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secure)
            .sameSite(SAME_SITE)
            .path(PATH)
            .maxAge(maxAgeSeconds)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /** A single save of a new token -- never save(null) then save -- so the last Set-Cookie wins. */
  private void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
    csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
  }
}
