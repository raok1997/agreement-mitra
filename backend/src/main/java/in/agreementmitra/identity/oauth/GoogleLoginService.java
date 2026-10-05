package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.GoogleTokenValidator.GoogleIdentity;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Drives the backend-mediated Google OAuth (OIDC) Authorization-Code + PKCE handshake. The SPA
 * never sees a Google token. Java-{@code public} so the {@code api} controller (a sibling package)
 * can inject it; still Modulith-internal.
 *
 * <p>{@link #start()} mints a single-use {@code state} + PKCE pair and a login-binding nonce,
 * persists them hashed, and returns the Google authorization URL to 302 to plus the nonce for the
 * browser's login cookie. {@link #handleCallback(String, String, String)} atomically consumes the
 * state only for the browser holding that nonce, exchanges the code, fully validates the ID token,
 * find-or-creates the identity, and mints a single-use handoff bound to the same nonce -- returning
 * only the handoff (never a token, never the session). No token, code, {@code state}, verifier,
 * nonce, or unredacted email is ever logged.
 */
@Service
public class GoogleLoginService {

  private static final Logger log = LoggerFactory.getLogger(GoogleLoginService.class);
  private static final String GOOGLE_PROVIDER = "GOOGLE";
  private static final String SCOPES = "openid email profile";

  private final OauthLoginStateRepository loginStates;
  private final HandoffService handoffService;
  private final GoogleTokenExchange tokenExchange;
  private final GoogleTokenValidator tokenValidator;
  private final IdentityService identityService;
  private final SecretTokens secretTokens;
  private final TokenHasher hasher;
  private final AuthProperties properties;

  GoogleLoginService(
      OauthLoginStateRepository loginStates,
      HandoffService handoffService,
      GoogleTokenExchange tokenExchange,
      GoogleTokenValidator tokenValidator,
      IdentityService identityService,
      SecretTokens secretTokens,
      TokenHasher hasher,
      AuthProperties properties) {
    this.loginStates = loginStates;
    this.handoffService = handoffService;
    this.tokenExchange = tokenExchange;
    this.tokenValidator = tokenValidator;
    this.identityService = identityService;
    this.secretTokens = secretTokens;
    this.hasher = hasher;
    this.properties = properties;
  }

  /**
   * Begin a login: persist a single-use login state (random {@code state} + PKCE verifier +
   * login-binding nonce, stored only as hashes / a short-lived secret) and return the Google
   * authorization URL with the raw nonce. The raw {@code state} is embedded in the URL for Google
   * to echo back; the raw nonce goes only into the browser's HttpOnly login cookie. Only hashes of
   * both are stored.
   */
  @Transactional
  public StartRedirect start() {
    AuthProperties.Google google = properties.google();
    requireConfigured(google);
    String state = secretTokens.newToken();
    String codeVerifier = secretTokens.newToken();
    String codeChallenge = secretTokens.pkceChallenge(codeVerifier);
    String bindingNonce = secretTokens.newToken();

    loginStates.save(
        OauthLoginState.create(
            hasher.hash(state),
            codeVerifier,
            google.redirectUri(),
            Instant.now().plus(properties.loginStateTtl()),
            hasher.hash(bindingNonce)));

    // No response_mode: Google's default (query) makes its redirect a cross-site top-level GET,
    // which carries the SameSite=Lax login-binding cookie. form_post would be a cross-site POST
    // that
    // carries no Lax cookie, and every callback would then fail the binding check.
    String authorizationUri =
        UriComponentsBuilder.fromUriString(google.authorizationUri())
            .queryParam("response_type", "code")
            .queryParam("client_id", google.clientId())
            .queryParam("redirect_uri", google.redirectUri())
            .queryParam("scope", SCOPES)
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("access_type", "online")
            // Always show Google's account chooser. Signing out ends OUR session, not the
            // browser's Google session, so without this the next "Sign in with Google" on a shared
            // computer silently re-enters the previous person's account.
            .queryParam("prompt", "select_account")
            .build()
            .encode()
            .toUriString();

    log.debug("Login started; redirecting to Google authorization endpoint");
    return new StartRedirect(authorizationUri, bindingNonce);
  }

  /**
   * Complete the callback: verify the {@code state} against an unconsumed, unexpired login state
   * bound to this browser's login-binding nonce (consumed atomically), exchange the {@code code}
   * for tokens, validate the ID token, find-or-create the identity, and mint a single-use handoff
   * bound to the same nonce. Throws {@link InvalidLoginException} on any failure -- materializing
   * no handoff or session -- with no detail leaked.
   */
  @Transactional
  public HandoffIssued handleCallback(String code, String state, String bindingNonce) {
    requireConfigured(properties.google());
    if (isBlank(code) || isBlank(state)) {
      throw new InvalidLoginException("callback missing code or state");
    }
    if (isBlank(bindingNonce)) {
      throw new InvalidLoginException("login binding missing");
    }
    Instant now = Instant.now();
    String stateHash = hasher.hash(state);
    if (loginStates.consume(stateHash, hasher.hash(bindingNonce), now) != 1) {
      // Unknown, already consumed, expired, or another browser's -- indistinguishable to the
      // caller.
      throw new InvalidLoginException("unknown, reused, expired, or unbound login state");
    }
    OauthLoginState loginState =
        loginStates
            .findByStateHash(stateHash)
            .orElseThrow(() -> new InvalidLoginException("login state vanished after consume"));

    String idToken = tokenExchange.exchangeForIdToken(code, loginState.codeVerifier());
    GoogleIdentity google = tokenValidator.validate(idToken);

    UUID identityId =
        identityService.findOrCreate(
            GOOGLE_PROVIDER, google.subject(), google.email(), true, google.name());

    // The consume above proved hash(bindingNonce) equals the state row's binding, so the same raw
    // nonce binds the handoff to the same browser (D3).
    String handoff = handoffService.issue(identityId, bindingNonce);

    log.debug("Login callback completed; handoff minted for identity {}", identityId);
    return new HandoffIssued(handoff);
  }

  /**
   * Fail closed when Google login is not configured. Login is optional (the app runs without it),
   * so this is enforced at request time rather than at startup -- and it never falls back to a real
   * project: a blank client id/secret simply refuses the handshake.
   */
  private static void requireConfigured(AuthProperties.Google google) {
    if (isBlank(google.clientId()) || isBlank(google.clientSecret())) {
      throw new InvalidLoginException("Google login is not configured");
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * The Google authorization URL to 302 the browser to, and the raw login-binding nonce for the
   * browser's HttpOnly login cookie. {@code toString()} omits both the nonce and the URL (which
   * carries the raw {@code state}), so logging the record can never leak either.
   */
  public record StartRedirect(String authorizationUri, String bindingNonce) {
    @Override
    public String toString() {
      return "StartRedirect{}";
    }
  }

  /** The one-time handoff to carry in the SPA redirect. {@code toString()} omits it. */
  public record HandoffIssued(String handoff) {
    @Override
    public String toString() {
      return "HandoffIssued{}";
    }
  }
}
