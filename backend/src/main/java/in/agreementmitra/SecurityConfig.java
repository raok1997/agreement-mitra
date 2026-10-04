package in.agreementmitra;

import in.agreementmitra.identity.api.SessionAuthenticationFilter;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Application-wide HTTP security baseline (CR-5). Lives in the root package - NOT a sub-package -
 * so Spring Modulith does not classify it as a module (direct sub-packages of the root become
 * application modules). Fail-closed: every request is denied unless an explicit rule permits it.
 *
 * <p>The webhook is permitted here because the aggregator cannot present credentials - its real
 * authorization is the body-MAC (HMAC-SHA1) verification in the signing service, which runs before
 * any side effect. The signing create-request route is STAFF-only: signing starts server-side once
 * the e-stamp is attached, so the route is a staff retry hatch rather than a customer path.
 *
 * <p><b>Rate limits</b> (anonymous-surface-abuse-controls): {@link CrossSiteRequestGuard} and then
 * {@link RouteRateLimitFilter} run in this chain after {@code CsrfFilter}, refusing browser-marked
 * cross-site requests uncounted and then classifying every route; the request-body ceiling runs as
 * a servlet filter ahead of the chain.
 *
 * <p><b>Role-based authorization</b> (manual-estamp-upload CR): the staff surface under {@code
 * /api/staff/} requires the STAFF role, evaluated <em>here</em> - in the filter chain, BEFORE any
 * handler runs and therefore before any resource lookup - so a refusal can never act as an
 * existence oracle for an agreement the caller does not own. The role rides the authenticated
 * principal as a {@code ROLE_STAFF} authority the session filter reads from the identity record; no
 * client value contributes to it.
 *
 * <p><b>401 vs 403.</b> Historically every denial rendered 403 (Spring Security's default {@link
 * Http403ForbiddenEntryPoint} applies when no authentication mechanism is configured). The staff
 * surface must distinguish "who are you?" from "not allowed", so a {@link
 * DelegatingAuthenticationEntryPoint} returns <b>401</b> for an unauthenticated caller on {@code
 * /api/staff/**} only, and every other path keeps the pre-existing 403 behaviour byte-for-byte. An
 * authenticated caller lacking the role still gets 403 from the access-denied handler on any path.
 *
 * <p><b>CSRF</b> (cookie-session-auth CR). The session is an HttpOnly cookie the browser attaches
 * automatically, so CSRF protection is ON for every unsafe method, anonymous or authenticated: a
 * double-submit token ({@code __Host-XSRF-TOKEN} cookie echoed as {@code X-XSRF-TOKEN}), read from
 * the header only and issued eagerly on any response. The token repository is identity's (it owns
 * both browser-session cookies). Exactly two routes are exempt -- the HMAC-authenticated webhooks,
 * matched by method and exact path. CSRF verification runs before authentication and authorization,
 * so an unsafe request with no valid token gets the CSRF 403 ({@code
 * urn:agreementmitra:problem:csrf}) before any 401 or role 403 is decided. There is deliberately no
 * property, profile, or alternate chain that disables it -- tests supply tokens instead.
 */
@Configuration
class SecurityConfig {

  /**
   * The staff-only surface. Matched as a prefix so every future staff sub-path inherits the 401.
   */
  private static final String STAFF_PATH_PREFIX = "/api/staff/";

  /** The exact stamp-intake route. Exact method+path, so no other staff sub-path is opened. */
  private static final String STAMP_INTAKE_PATH = "/api/staff/estamp";

  /** The exact stamp-queue route backing the staff console. */
  private static final String STAMP_QUEUE_PATH = "/api/staff/estamp/queue";

  /** The payment-gate mode read: is payment actually being enforced right now? */
  private static final String PAYMENT_GATE_PATH = "/api/staff/payments/gate";

  /** One agreement's payment state. */
  private static final String PAYMENT_STATE_PATH = "/api/staff/payments/*";

  /** Record a manual payment confirmation. */
  private static final String PAYMENT_CONFIRM_PATH = "/api/staff/payments/*/confirm";

  /** Waive payment - proceed deliberately without money. */
  private static final String PAYMENT_WAIVE_PATH = "/api/staff/payments/*/waive";

  /** Extend a still-pending signing window on the same provider transaction. */
  private static final String SIGNING_EXTEND_PATH = "/api/staff/signing/*/extend";

  /** Ask the provider to re-send its invitations for the same transaction. */
  private static final String SIGNING_RESEND_PATH = "/api/staff/signing/*/resend";

  /** Per-recipient delivery records for one agreement (redacted addresses, no document). */
  private static final String DELIVERY_STATUS_PATH = "/api/staff/deliveries/*";

  /** One agreement's terminal fulfilment (closure) state. */
  private static final String DELIVERY_CLOSURE_PATH = "/api/staff/deliveries/*/closure";

  /** Deliberate, attributed re-send of one recipient's copy. */
  private static final String DELIVERY_RESEND_PATH = "/api/staff/deliveries/*/resend";

  /** The party-facing signed-agreement download. Owner-scoped in the handler, like the read. */
  private static final String SIGNED_DOCUMENT_PATH = "/api/agreements/*/signed-document";

  /** The role a stamp upload requires. {@code hasRole} prefixes it with {@code ROLE_}. */
  private static final String STAFF_ROLE = "STAFF";

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<SessionAuthenticationFilter> sessionAuthenticationFilter,
      ObjectProvider<CsrfTokenRepository> csrfTokenRepository,
      ObjectProvider<CrossSiteRequestGuard> crossSiteRequestGuard,
      ObjectProvider<RouteRateLimitFilter> routeRateLimitFilter)
      throws Exception {
    http.csrf(
            c ->
                c.csrfTokenRepository(
                        csrfTokenRepository.getIfAvailable(SecurityConfig::fallbackCsrfRepository))
                    .csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())
                    // Server-to-server webhooks carry no browser cookie and are authorized by
                    // their own HMAC/key before any side effect. Exact method + path only: the
                    // SPA-called payment callback and any near path stay protected.
                    .ignoringRequestMatchers(
                        PathPatternRequestMatcher.withDefaults()
                            .matcher(HttpMethod.POST, "/api/webhooks/esign"),
                        PathPatternRequestMatcher.withDefaults()
                            .matcher(HttpMethod.POST, "/api/webhooks/razorpay")))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(authenticationEntryPoint())
                    .accessDeniedHandler(new CsrfAwareAccessDeniedHandler()));
    // Opaque-session authentication (google-oauth-login CR): populates the SecurityContext from the
    // HttpOnly session cookie before authorization runs (cookie-session-auth: the cookie is the
    // only transport; an Authorization header is ignored). Strictly additive -- a no-op
    // when no valid session is presented, so the deny-by-default posture is unchanged for every
    // existing route. Injected via ObjectProvider so a web slice / module slice that does NOT load
    // the identity module still builds this chain (the filter is simply absent there); the full
    // application context always has it.
    sessionAuthenticationFilter.ifAvailable(
        filter -> http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class));
    // Route-class rate limits (anonymous-surface-abuse-controls D4): AFTER CsrfFilter, so a request
    // refused for CSRF costs its source nothing, and BEFORE the session filter above, so a flood of
    // junk session cookies is bounded before the lookup reaches Postgres.
    // A request the browser marks cross-site is refused first and never counted, so a hostile page
    // cannot spend a visitor's budget with embedded GETs (CSRF tokens guard only unsafe methods).
    crossSiteRequestGuard.ifAvailable(guard -> http.addFilterAfter(guard, CsrfFilter.class));
    routeRateLimitFilter.ifAvailable(
        filter ->
            http.addFilterAfter(
                filter,
                crossSiteRequestGuard.getIfAvailable() != null
                    ? CrossSiteRequestGuard.class
                    : CsrfFilter.class));
    return http.authorizeHttpRequests(
            auth ->
                // Permit the error dispatch path. Spring Security 6 re-authorizes the internal
                // dispatch to /error that Boot performs when a handler throws; without this, a
                // PERMITTED endpoint that errors has its /error dispatch re-evaluated against
                // anyRequest().denyAll() and the real status (e.g. 500) is masked as 403. The
                // BasicErrorController leaks nothing (no stack/message by Boot default), and this
                // does not weaken the posture - every real business path is still governed below.
                auth.requestMatchers("/error")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .permitAll()
                    // Aggregator can't present a session; authorized at the app layer by the active
                    // provider adapter - a body MAC over the document id (Leegality) or a
                    // per-transaction key in the `webhook-security-key` header (ZOOP v5) - which
                    // runs before any side effect.
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/esign")
                    .permitAll()
                    // Payment gateway can't present a session either (razorpay-payment CR). Its
                    // real
                    // authorization is the HMAC-SHA256 signature over the RAW body, keyed by the
                    // WEBHOOK secret (never the API key secret), verified before any side effect.
                    // Rejected requests change nothing and a verified one is acknowledged
                    // identically whether or not the order is ours - no existence oracle.
                    .requestMatchers(HttpMethod.POST, "/api/webhooks/razorpay")
                    .permitAll()
                    // Staff e-stamp intake (manual-estamp-upload CR): STAFF role required, decided
                    // here so authorization strictly precedes the agreement lookup. Exact
                    // method+path only (NOT /api/staff/**), so any future staff sub-path stays
                    // denied by default; the 401 entry point below is scoped to the /api/staff/
                    // prefix so an unauthenticated caller is told to authenticate rather than being
                    // told "forbidden".
                    .requestMatchers(HttpMethod.POST, STAMP_INTAKE_PATH)
                    .hasRole(STAFF_ROLE)
                    // The staff fulfilment queue behind the same role gate. A non-staff caller is
                    // refused here, so they learn neither how many orders exist nor anything about
                    // one.
                    .requestMatchers(HttpMethod.GET, STAMP_QUEUE_PATH)
                    .hasRole(STAFF_ROLE)
                    // Payment surface (zoop-aadhaar-esign CR / payment-gate). STAFF-only, decided
                    // here so authorization strictly precedes any agreement lookup - a non-staff
                    // caller learns nothing about whether an agreement exists or what was paid.
                    // Payment state is server-managed; there is no client-settable route to it and
                    // no payment-gateway credential anywhere on this surface. The gate-mode read is
                    // listed BEFORE the by-id read so `/gate` is not swallowed by the `*` matcher.
                    .requestMatchers(HttpMethod.GET, PAYMENT_GATE_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.GET, PAYMENT_STATE_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.POST, PAYMENT_CONFIRM_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.POST, PAYMENT_WAIVE_PATH)
                    .hasRole(STAFF_ROLE)
                    // Staff remedies for a stuck in-flight signing request. Both act on the SAME
                    // provider transaction (no new document, no second vendor charge), so they are
                    // staff operations rather than anything a customer can trigger repeatedly.
                    .requestMatchers(HttpMethod.POST, SIGNING_EXTEND_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.POST, SIGNING_RESEND_PATH)
                    .hasRole(STAFF_ROLE)
                    // Delivery diagnostics + the manual re-send lever (signed-delivery-and-closure
                    // CR). STAFF-only, decided here so authorization strictly precedes any delivery
                    // or agreement lookup - a non-staff caller learns nothing about whether an
                    // agreement exists or who it was sent to. The view carries a REDACTED recipient
                    // address and never the document itself. The more specific /closure path is
                    // listed BEFORE the by-id read so it is not swallowed by the `*` matcher.
                    .requestMatchers(HttpMethod.GET, DELIVERY_CLOSURE_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.GET, DELIVERY_STATUS_PATH)
                    .hasRole(STAFF_ROLE)
                    .requestMatchers(HttpMethod.POST, DELIVERY_RESEND_PATH)
                    .hasRole(STAFF_ROLE)
                    // Exact create-request path only - NOT /api/signing/** - so future signing
                    // sub-paths are denied by default. STAFF-only (anonymous-surface-abuse-controls
                    // D7): signing is initiated server-side once the e-stamp is attached, so this
                    // is a staff retry hatch, not a customer path. CSRF still applies.
                    .requestMatchers(HttpMethod.POST, "/api/signing/*/request")
                    .hasRole(STAFF_ROLE)
                    // Per-party signing progress (zoop-aadhaar-esign CR). Permitted here and
                    // owner-scoped in the handler, exactly like GET /api/agreements/* - the chain
                    // cannot see the row's owner, and a STAFF caller must also be able to read it.
                    // The handler returns the same 404 for "not yours" as for "unknown", so this is
                    // not a cross-customer oracle. The view carries no eKYC PII and no signing URL.
                    .requestMatchers(HttpMethod.GET, "/api/signing/*/progress")
                    .permitAll()
                    // Ownership surface (agreement-ownership CR): save / list / edit require a live
                    // session (the identity id principal). These MUST be ordered BEFORE the broad
                    // agreement permits below so the capability read + anonymous create stay open
                    // while list/claim/edit are gated. Owner-vs-capability read scoping for GET
                    // /{id} is enforced in the handler (D5), not here -- the chain can't see the
                    // row's owner. Exact paths only (NOT /api/agreements/**).
                    .requestMatchers(HttpMethod.GET, "/api/agreements")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/claim")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/agreements/*")
                    .authenticated()
                    // Sandbox agreement surface - scoped to the exact create + read-by-id paths
                    // (NOT /api/agreements/**) so future sub-paths stay denied by default. These
                    // are unauthenticated today; TEMPORARY - tighten when an auth mechanism lands.
                    .requestMatchers(HttpMethod.POST, "/api/agreements")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*")
                    .permitAll()
                    // The party-facing signed-agreement download (signed-delivery-and-closure CR).
                    // Permitted here and owner-scoped in the HANDLER, exactly like GET
                    // /api/agreements/* - the chain cannot see the row's owner, and a STAFF caller
                    // must also be able to fetch it for support. The handler evaluates
                    // authorization BEFORE the artifact lookup and returns the same 404 for "not
                    // yours" as for "unknown", so this is not an existence oracle. The bucket stays
                    // private: bytes are streamed through this request, never via a presigned URL.
                    //
                    // NOTE what is absent: there is NO audit-trail route. The audit trail carries
                    // eKYC-derived detail and is an internal evidentiary artifact, so it is left to
                    // the fail-closed anyRequest().denyAll() below rather than being opened and
                    // then refused.
                    .requestMatchers(HttpMethod.GET, SIGNED_DOCUMENT_PATH)
                    .permitAll()
                    // Document preview (CR-3b) - scoped to the exact sub-path (NOT
                    // /api/agreements/**). Anonymous like the rest of the agreement/draft surface;
                    // TEMPORARY - tighten (ownership) when an auth mechanism lands. Renders on
                    // demand and stores nothing.
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*/preview")
                    .permitAll()
                    // Generate-as-draft (CR-3c) - render + store as the signable draft. Scoped to
                    // the exact sub-path. Anonymous like the rest of the agreement/draft surface;
                    // TEMPORARY - tighten (ownership) when an auth mechanism lands. Overwrite until
                    // signing, then locked (409) by the shared draft freeze rule.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/document")
                    .permitAll()
                    // Finalise (place the order) - the end of the customer's involvement. Scoped to
                    // the exact sub-path. Anonymous like the rest of the drafting surface;
                    // TEMPORARY - tighten with them when ownership authorization lands, and move
                    // behind payment confirmation when the payment gate arrives.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/finalise")
                    .permitAll()
                    // Customer payment surface (razorpay-payment CR). Permitted here and
                    // owner-scoped in the HANDLER, exactly like GET /api/agreements/* - the chain
                    // cannot see the row's owner, and a STAFF caller must also be able to act. The
                    // handler returns the same 404 for "not yours" as for "unknown", so this is not
                    // a cross-customer oracle. Nothing on this surface accepts an amount, and no
                    // response carries a key secret or webhook secret. Exact sub-paths only.
                    // Recovery request (post-payment-continuity CR). Anonymous by necessity: the
                    // customer this serves has no account and no identifier - the tracking
                    // reference is all they have. Safe to open because the endpoint returns 202
                    // with an empty body in EVERY case and any mail it produces goes to the
                    // agreement's own parties, never to the requester. Exact path, and listed
                    // BEFORE the /api/agreements/* matchers so it is not swallowed by them.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/recovery")
                    .permitAll()
                    // Pre-payment contact confirmation (post-payment-continuity CR, design D16).
                    // Anonymous like the rest of this surface and scoped to the exact sub-path. The
                    // route accepts CONTACTS ONLY and refuses an owned or frozen agreement, so it
                    // is strictly weaker than PUT /api/agreements/* (which stays authenticated):
                    // a caller holding the id can redirect their own agreement's notifications,
                    // which the id already entitles them to, but cannot touch rent, dates, or the
                    // party list.
                    .requestMatchers(HttpMethod.PATCH, "/api/agreements/*/contacts")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/payment/order")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/payment/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*/payment")
                    .permitAll()
                    // Stamp quote before payment (state-stamp-duty-quoting). Same posture as the
                    // payment progress read above: permitted here, owner-scoped in the handler
                    // with the same 404 for "not yours" and "unknown", so no amount is disclosed to
                    // a caller who may not see the payment. Read-only; carries no party data.
                    .requestMatchers(HttpMethod.GET, "/api/agreements/*/stamp-quote")
                    .permitAll()
                    // Draft upload - scoped to the exact sub-path (NOT /api/agreements/**) so the
                    // posture stays fail-closed. Anonymous like the rest of the capability surface
                    // (the id is the credential); rate limited as a capability write.
                    .requestMatchers(HttpMethod.POST, "/api/agreements/*/draft")
                    .permitAll()
                    // Form-projection schema (template-form-projection) - a public read of
                    // system-owned metadata (field keys/labels/widgets/validation): no PII, no
                    // signer data, no auth needed. Exact path only (NOT /api/templates/**) so
                    // future
                    // template sub-paths stay denied by default.
                    .requestMatchers(HttpMethod.GET, "/api/templates/form")
                    .permitAll()
                    // Stateless document projection (document-projection-render) -- renders a
                    // posted
                    // working set to inline HTML/PDF and stores nothing. Exact method+path only
                    // (NOT
                    // /api/templates/**) so the posture stays fail-closed. Anonymous like the rest
                    // of
                    // the render surface; server-side schema validation caps the render, the
                    // render route class bounds it per source, and render admission control
                    // refuses rather than queues when every slot is busy.
                    .requestMatchers(HttpMethod.POST, "/api/templates/document/preview")
                    .permitAll()
                    // Template catalog (template-catalog) -- public reads of system-owned
                    // template metadata (no PII, no signer data, no auth needed). Exact paths
                    // only: the browse list and one entry by id. NOT /api/templates/** so future
                    // template sub-paths stay denied by default.
                    .requestMatchers(HttpMethod.GET, "/api/templates")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/templates/*")
                    .permitAll()
                    // Eligible jurisdictions (jurisdiction-checkout-gating) -- which states can be
                    // stamped/eSigned, so the SPA can mark the rest draft-and-download only before
                    // a customer fills in a whole agreement. Public, static, and carries no
                    // agreement data: it is disclosure, never the control (the four server-side
                    // gates are). Exact path only, so no future sub-path is opened by accident.
                    .requestMatchers(HttpMethod.GET, "/api/jurisdictions")
                    .permitAll()
                    // Google login handshake (google-oauth-login CR) -- reachable without a session
                    // (there is none yet during login). Exact method+path only so no other
                    // /api/auth sub-path is opened by accident. The callback + exchange are safe to
                    // permit: the callback validates a single-use state and the ID token; the
                    // exchange consumes a single-use handoff. No Google token or session value ever
                    // rides these URLs into a log.
                    .requestMatchers(HttpMethod.GET, "/api/auth/google/start")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/auth/google/callback")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/session/exchange")
                    .permitAll()
                    // CSRF bootstrap (cookie-session-auth): 204, no body; the eager CSRF handler
                    // sets the token cookie. Side-effect-free.
                    .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                    .permitAll()
                    // Logout is reachable without a live session so a stale HttpOnly cookie can
                    // still be cleared (JS cannot). It stays CSRF-protected, so it cannot be used
                    // for forced logout.
                    .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                    .permitAll()
                    // Identity surface behind a live session: the session-authentication filter
                    // above sets the principal from a valid session cookie; without one this is
                    // denied. Exact path only.
                    .requestMatchers(HttpMethod.GET, "/api/auth/me")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        // Keep Spring Security's default hardening response headers (nosniff, no-cache, etc.).
        .headers(Customizer.withDefaults())
        .build();
  }

  /**
   * Used only when the identity module is absent (module slices), which have no session to protect.
   * MUST match identity's {@code AuthWebConfig.csrfTokenRepository} bean in secure mode and the
   * test {@code CsrfTestInterceptor}'s cookie name, so a slice that POSTs behaves like the full
   * app.
   */
  private static CsrfTokenRepository fallbackCsrfRepository() {
    CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieName("__Host-XSRF-TOKEN");
    repository.setHeaderName("X-XSRF-TOKEN");
    repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax").path("/"));
    return repository;
  }

  /**
   * 401 on the staff surface, the historical 403 everywhere else. Scoping the change to {@code
   * /api/staff/} keeps the pre-existing posture of every other route bit-for-bit identical (the
   * baseline test pins unmapped paths at 403), while the staff endpoint can honour the spec's
   * 401-for-anonymous / 403-for-wrong-role distinction.
   */
  private static AuthenticationEntryPoint authenticationEntryPoint() {
    RequestMatcher staffSurface =
        (RequestMatcher) request -> request.getRequestURI().startsWith(STAFF_PATH_PREFIX);
    LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> byPath = new LinkedHashMap<>();
    byPath.put(staffSurface, new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
    DelegatingAuthenticationEntryPoint delegating = new DelegatingAuthenticationEntryPoint(byPath);
    delegating.setDefaultEntryPoint(new Http403ForbiddenEntryPoint());
    return delegating;
  }
}
