package in.agreementmitra.identity.session;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.IdentityService.IdentitySummary;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.oauth.InvalidLoginException;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints, authenticates, and revokes opaque server-side sessions -- the shared, provider-agnostic
 * session layer. Java-{@code public} so the {@code api} controller and the {@code session} filter
 * can inject it; still Modulith-internal.
 *
 * <p>The session value is 256 bits of strong randomness, returned to the caller exactly once at
 * {@link #exchange(String)} and persisted only as a keyed hash. Authentication re-hashes a
 * presented bearer value and looks up a live row; logout deletes it. No value is ever logged.
 */
@Service
public class SessionService {

  private static final Logger log = LoggerFactory.getLogger(SessionService.class);

  private final AuthSessionRepository sessions;
  private final HandoffService handoffService;
  private final IdentityService identityService;
  private final SecretTokens secretTokens;
  private final TokenHasher hasher;
  private final AuthProperties properties;

  SessionService(
      AuthSessionRepository sessions,
      HandoffService handoffService,
      IdentityService identityService,
      SecretTokens secretTokens,
      TokenHasher hasher,
      AuthProperties properties) {
    this.sessions = sessions;
    this.handoffService = handoffService;
    this.identityService = identityService;
    this.secretTokens = secretTokens;
    this.hasher = hasher;
    this.properties = properties;
  }

  /**
   * Consume a single-use handoff and mint an opaque session for the bound identity. Returns the
   * session value (once) plus the identity summary. Throws {@link InvalidLoginException} if the
   * handoff is unknown, reused, or expired -- minting nothing.
   */
  @Transactional
  public SessionIssued exchange(String handoff) {
    UUID identityId = handoffService.consume(handoff);
    IdentitySummary summary =
        identityService
            .summary(identityId)
            .orElseThrow(() -> new InvalidLoginException("identity missing for handoff"));

    String value = secretTokens.newToken();
    sessions.save(
        AuthSession.create(
            identityId, hasher.hash(value), Instant.now().plus(properties.sessionTtl())));

    log.debug("Session minted for identity {}", identityId);
    return new SessionIssued(value, summary);
  }

  /**
   * Authenticate a presented bearer value: re-hash it, resolve a live unexpired session, touch its
   * last-seen, and return the owning identity id. Returns empty when the value is absent, unknown,
   * or expired -- leaving the request unauthenticated. Never logs the value.
   */
  @Transactional
  public Optional<UUID> authenticate(String bearerValue) {
    if (bearerValue == null || bearerValue.isBlank()) {
      return Optional.empty();
    }
    Instant now = Instant.now();
    return sessions
        .findByValueHash(hasher.hash(bearerValue))
        .filter(session -> session.isLive(now))
        .map(
            session -> {
              session.touch(now);
              sessions.save(session);
              return session.identityId();
            });
  }

  /** Revoke the caller's session (delete the row). Idempotent; never logs the value. */
  @Transactional
  public void revoke(String bearerValue) {
    if (bearerValue == null || bearerValue.isBlank()) {
      return;
    }
    sessions.deleteByValueHash(hasher.hash(bearerValue));
  }

  /** The freshly-minted session value (returned once) plus the caller's identity summary. */
  public record SessionIssued(String value, IdentitySummary me) {}
}
