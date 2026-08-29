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
 * <p>{@link #start()} mints a single-use {@code state} + PKCE pair, persists them hashed, and
 * returns the Google authorization URL to 302 to. {@link #handleCallback(String, String)}
 * atomically consumes the state, exchanges the code, fully validates the ID token, find-or-creates
 * the identity, and mints a single-use handoff -- returning only the handoff (never a token, never
 * the session). No token, code, {@code state}, verifier, or unredacted email is ever logged.
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
   * Begin a login: persist a single-use login state (random {@code state} + PKCE verifier, stored
   * only as hashes / a short-lived secret) and return the Google authorization URL. The raw {@code
   * state} is embedded in the URL for Google to echo back; only its hash is stored.
   */
  @Transactional
  public StartRedirect start() {
    AuthProperties.Google google = properties.google();
    requireConfigured(google);
    String state = secretTokens.newToken();
    String codeVerifier = secretTokens.newToken();
    String codeChallenge = secretTokens.pkceChallenge(codeVerifier);

    loginStates.save(
        OauthLoginState.create(
            hasher.hash(state),
            codeVerifier,
            google.redirectUri(),
            Instant.now().plus(properties.loginStateTtl())));

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
            .build()
            .encode()
            .toUriString();

    log.debug("Login started; redirecting to Google authorization endpoint");
    return new StartRedirect(authorizationUri);
  }

  /**
   * Complete the callback: verify the {@code state} against an unconsumed, unexpired login state
   * (consumed atomically), exchange the {@code code} for tokens, validate the ID token, find-or-
   * create the identity, and mint a single-use handoff. Throws {@link InvalidLoginException} on any
   * failure -- materializing no handoff or session -- with no detail leaked.
   */
  @Transactional
  public HandoffIssued handleCallback(String code, String state) {
    requireConfigured(properties.google());
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      throw new InvalidLoginException("callback missing code or state");
    }
    Instant now = Instant.now();
    String stateHash = hasher.hash(state);
    if (loginStates.consume(stateHash, now) != 1) {
      // Unknown, already consumed, or expired -- indistinguishable to the caller.
      throw new InvalidLoginException("unknown, reused, or expired login state");
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

    String handoff = handoffService.issue(identityId);

    log.debug("Login callback completed; handoff minted for identity {}", identityId);
    return new HandoffIssued(handoff, properties.google().spaCallbackUri());
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

  /** The Google authorization URL to 302 the browser to. */
  public record StartRedirect(String authorizationUri) {}

  /**
   * The one-time handoff to carry in the SPA redirect, and the SPA callback route to send it to.
   */
  public record HandoffIssued(String handoff, String spaCallbackUri) {}
}
