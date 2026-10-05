package in.agreementmitra.identity.api;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.api.AuthDtos.MeResponse;
import in.agreementmitra.identity.api.AuthDtos.SessionExchangeRequest;
import in.agreementmitra.identity.api.AuthDtos.SessionResponse;
import in.agreementmitra.identity.oauth.GoogleLoginService;
import in.agreementmitra.identity.oauth.GoogleLoginService.HandoffIssued;
import in.agreementmitra.identity.oauth.GoogleLoginService.StartRedirect;
import in.agreementmitra.identity.oauth.InvalidLoginException;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.identity.session.SessionService.SessionIssued;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public HTTP surface of the identity module's optional Google login. Backend-mediated OAuth: the
 * SPA never sees a Google token. The handshake routes ({@code /google/start}, {@code
 * /google/callback}, {@code /session/exchange}), the CSRF bootstrap ({@code /csrf}) and logout are
 * {@code permitAll} in the security baseline; me requires an authenticated session. The session
 * travels only in the HttpOnly cookie owned by {@link SessionCookies} -- never in a body.
 *
 * <p>No token, authorization code, session value, handoff, login-binding nonce, PKCE verifier, or
 * unredacted email is ever logged, and none is ever placed in an error body. A refused callback
 * 302s to the SPA's {@code #error} route and a refused exchange (or an unconfigured {@code /start})
 * returns the fixed 401 -- each identical for every cause (no enumeration oracle, no
 * validation-detail leak). The login is bound to the browser that started it by the login-binding
 * cookie {@link SessionCookies} owns (login-browser-binding).
 */
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final GoogleLoginService googleLoginService;
  private final SessionService sessionService;
  private final IdentityService identityService;
  private final SessionCookies sessionCookies;
  private final AuthProperties properties;

  AuthController(
      GoogleLoginService googleLoginService,
      SessionService sessionService,
      IdentityService identityService,
      SessionCookies sessionCookies,
      AuthProperties properties) {
    this.googleLoginService = googleLoginService;
    this.sessionService = sessionService;
    this.identityService = identityService;
    this.sessionCookies = sessionCookies;
    this.properties = properties;
  }

  /**
   * Begin login: 302 to Google's consent screen (a single-use state + PKCE pair is persisted) and
   * set the login-binding cookie. A GET with side effects by design (login-browser-binding D5):
   * {@code CrossSiteRequestGuard} refuses it cross-site, and a forced call only replaces the
   * victim's nonce with one the attacker never sees.
   */
  @GetMapping("/api/auth/google/start")
  ResponseEntity<Void> start(HttpServletResponse httpResponse) {
    StartRedirect redirect = googleLoginService.start();
    sessionCookies.setLoginBinding(httpResponse, redirect.bindingNonce());
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(redirect.authorizationUri()))
        .build();
  }

  /**
   * Google's redirect target: validate the state, exchange the code, validate the ID token,
   * find-or-create the identity, mint a single-use handoff, and 302 back to the SPA carrying only
   * the handoff in the URL fragment (never a token, never the session).
   *
   * <p>A login refusal -- including a missing or different login-binding cookie -- 302s to the SPA
   * route with the fixed fragment {@code #error}, identical for every cause. This is a top-level
   * navigation, so a JSON problem body would render as raw text. The explicit fragment matters: a
   * {@code Location} without one inherits the request's fragment (RFC 9110 section 10.2.2), so a
   * link ending {@code #handoff=<attacker's>} would otherwise reach the SPA's exchange.
   * Infrastructure failures are not caught and keep their 500.
   */
  @GetMapping("/api/auth/google/callback")
  ResponseEntity<Void> callback(
      @RequestParam(name = "code", required = false) String code,
      @RequestParam(name = "state", required = false) String state,
      HttpServletRequest httpRequest) {
    String spaCallbackUri = properties.google().spaCallbackUri();
    URI target;
    try {
      HandoffIssued issued =
          googleLoginService.handleCallback(
              code, state, sessionCookies.readLoginBinding(httpRequest));
      target = URI.create(spaCallbackUri + "#handoff=" + issued.handoff());
    } catch (InvalidLoginException ex) {
      log.debug("Login/exchange rejected: {}", ex.getMessage());
      target = URI.create(spaCallbackUri + "#error");
    }
    return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
  }

  /**
   * Exchange the single-use handoff for an opaque session, delivered only as the HttpOnly cookie;
   * the body carries just the caller's summary. Order is load-bearing (D7): consume + mint first,
   * and only on success establish the cookie (which rotates CSRF and revokes any prior session). A
   * refused handoff throws before the browser's existing session or cookie is touched -- including
   * its own login-binding cookie, so its own login can still complete. Only a successful exchange
   * expires the binding.
   */
  @PostMapping("/api/auth/session/exchange")
  ResponseEntity<SessionResponse> exchange(
      @RequestBody SessionExchangeRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    SessionIssued issued =
        sessionService.exchange(
            request == null ? null : request.handoff(),
            sessionCookies.readLoginBinding(httpRequest));
    sessionCookies.establish(httpRequest, httpResponse, issued);
    sessionCookies.clearLoginBinding(httpResponse);
    return ResponseEntity.ok(new SessionResponse(toMe(issued.me())));
  }

  /**
   * CSRF bootstrap: 204 with no body. The eager CSRF handler in the security chain sets the token
   * cookie on any response to a browser that lacks one; this route just gives the SPA a
   * deterministic, side-effect-free request to trigger that before its first unsafe call.
   */
  @GetMapping("/api/auth/csrf")
  ResponseEntity<Void> csrf() {
    return ResponseEntity.noContent().build();
  }

  /** Return the authenticated caller's identity summary. Requires a live session. */
  @GetMapping("/api/auth/me")
  ResponseEntity<MeResponse> me(@AuthenticationPrincipal UUID identityId) {
    return identityService
        .summary(identityId)
        .map(this::toMe)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
  }

  /**
   * Revoke the cookie's session (if any), always expire the cookie, rotate CSRF; 204. Reachable
   * without a live session so a stale cookie can still be cleared -- JS cannot delete an HttpOnly
   * cookie. Still CSRF-protected, so it cannot be used for forced logout.
   */
  @PostMapping("/api/auth/logout")
  ResponseEntity<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    boolean revoked = true;
    try {
      sessionCookies.read(httpRequest).ifPresent(sessionService::revoke);
    } catch (RuntimeException e) {
      // The cookie is still expired below, so this browser is signed out either way; the row is
      // unreachable without its value and expires at its TTL. Report 500 so the SPA says so.
      log.warn("Logout revoke failed: {}", e.getClass().getSimpleName());
      revoked = false;
    }
    sessionCookies.clear(httpRequest, httpResponse);
    SecurityContextHolder.clearContext();
    return revoked
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
  }

  private MeResponse toMe(IdentitySummary summary) {
    return new MeResponse(
        summary.identityId().toString(),
        summary.displayName(),
        summary.email(),
        summary.role().name());
  }

  /**
   * Map an exchange failure (or an unconfigured {@code /start}) to a fixed 401 -- identical for
   * every cause so the response is no enumeration oracle and leaks no validation detail. The
   * callback catches its own refusals and redirects instead (see {@link #callback}). The exception
   * message is logged server-side only (already redacted by construction) and never reaches the
   * body.
   */
  @ExceptionHandler(InvalidLoginException.class)
  ProblemDetail handleInvalidLogin(InvalidLoginException ex) {
    log.debug("Login/exchange rejected: {}", ex.getMessage());
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Login could not be completed.");
    URI type = URI.create("urn:agreementmitra:problem:login-failed");
    problem.setType(type);
    problem.setTitle("Login failed");
    problem.setInstance(type);
    return problem;
  }
}
