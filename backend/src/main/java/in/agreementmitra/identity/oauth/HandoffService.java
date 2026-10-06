package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single-use login-handoff lifecycle. {@link #issue(UUID, String)} mints the one-time code
 * the callback carries in the SPA redirect (storing only its hash); {@link #consume(String,
 * String)} exchanges it exactly once for the bound identity id. Java-{@code public} so the {@code
 * session} exchange (a sibling package) can consume it; still Modulith-internal.
 *
 * <p>Both methods take the browser's <em>raw</em> login-binding nonce and hash it here, so the two
 * ends cannot disagree on which form they were given (login-browser-binding D3). Consumption is a
 * single conditional UPDATE that also matches the binding, so a reused, expired, or other-browser
 * handoff is refused race-safely and mints nothing.
 */
@Service
public class HandoffService {

  private final LoginHandoffRepository handoffs;
  private final SecretTokens secretTokens;
  private final TokenHasher hasher;
  private final AuthProperties properties;

  HandoffService(
      LoginHandoffRepository handoffs,
      SecretTokens secretTokens,
      TokenHasher hasher,
      AuthProperties properties) {
    this.handoffs = handoffs;
    this.secretTokens = secretTokens;
    this.hasher = hasher;
    this.properties = properties;
  }

  /**
   * Mint a one-time handoff bound to the identity and to the browser's login-binding nonce, store
   * only hashes, and return the raw code. A blank nonce is refused as a login refusal: it would
   * write a handoff that can never be consumed.
   */
  @Transactional
  public String issue(UUID identityId, String bindingNonce) {
    if (isBlank(bindingNonce)) {
      throw new InvalidLoginException("login binding missing");
    }
    String handoff = secretTokens.newToken();
    handoffs.save(
        LoginHandoff.create(
            hasher.hash(handoff),
            identityId,
            Instant.now().plus(properties.handoffTtl()),
            hasher.hash(bindingNonce)));
    return handoff;
  }

  /**
   * Atomically consume the handoff and return the bound identity id. Throws {@link
   * InvalidLoginException} when the handoff is unknown, already exchanged, expired, or bound to a
   * different browser, or when no binding nonce was presented -- so none of those mints a session.
   */
  @Transactional
  public UUID consume(String handoff, String bindingNonce) {
    if (isBlank(handoff)) {
      throw new InvalidLoginException("handoff missing");
    }
    if (isBlank(bindingNonce)) {
      throw new InvalidLoginException("login binding missing");
    }
    String handoffHash = hasher.hash(handoff);
    if (handoffs.consume(handoffHash, hasher.hash(bindingNonce), Instant.now()) != 1) {
      throw new InvalidLoginException("unknown, reused, expired, or unbound handoff");
    }
    return handoffs
        .findByHandoffHash(handoffHash)
        .map(LoginHandoff::identityId)
        .orElseThrow(() -> new InvalidLoginException("handoff vanished after consume"));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
