package in.agreementmitra.identity.oauth;

import in.agreementmitra.identity.AuthProperties;
import in.agreementmitra.identity.support.SecretTokens;
import in.agreementmitra.identity.support.TokenHasher;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single-use login-handoff lifecycle. {@link #issue(UUID)} mints the one-time code the
 * callback carries in the SPA redirect (storing only its hash); {@link #consume(String)} exchanges
 * it exactly once for the bound identity id. Java-{@code public} so the {@code session} exchange (a
 * sibling package) can consume it; still Modulith-internal.
 *
 * <p>Consumption is a single conditional UPDATE, so a reused or expired handoff is refused
 * race-safely and mints nothing.
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
   * Mint a one-time handoff bound to the identity, store only its hash, and return the raw code.
   */
  @Transactional
  public String issue(UUID identityId) {
    String handoff = secretTokens.newToken();
    handoffs.save(
        LoginHandoff.create(
            hasher.hash(handoff), identityId, Instant.now().plus(properties.handoffTtl())));
    return handoff;
  }

  /**
   * Atomically consume the handoff and return the bound identity id. Throws {@link
   * InvalidLoginException} when the handoff is unknown, already exchanged, or expired -- so a
   * reused or expired handoff mints no session.
   */
  @Transactional
  public UUID consume(String handoff) {
    if (handoff == null || handoff.isBlank()) {
      throw new InvalidLoginException("handoff missing");
    }
    String handoffHash = hasher.hash(handoff);
    if (handoffs.consume(handoffHash, Instant.now()) != 1) {
      throw new InvalidLoginException("unknown, reused, or expired handoff");
    }
    return handoffs
        .findByHandoffHash(handoffHash)
        .map(LoginHandoff::identityId)
        .orElseThrow(() -> new InvalidLoginException("handoff vanished after consume"));
  }
}
