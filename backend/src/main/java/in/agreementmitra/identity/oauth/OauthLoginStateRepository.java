package in.agreementmitra.identity.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link OauthLoginState}. Package-private -- Modulith-internal. */
interface OauthLoginStateRepository extends JpaRepository<OauthLoginState, UUID> {

  Optional<OauthLoginState> findByStateHash(String stateHash);

  /**
   * Atomically consume a login state: mark it consumed only if it is currently unconsumed and
   * unexpired. Returns the number of rows updated -- {@code 1} means this caller won the single-use
   * race, {@code 0} means the state was unknown, already consumed, expired, or bound to another
   * browser. Doing the guard in one conditional UPDATE (not read-then-write) makes the single-use
   * guarantee race-safe, and makes a binding mismatch indistinguishable from an unknown state. A
   * row with a null binding hash never matches, so it is never consumed (login-browser-binding D2).
   */
  @Modifying
  @Query(
      "update OauthLoginState s set s.consumedAt = :now "
          + "where s.stateHash = :stateHash and s.browserBindingHash = :bindingHash "
          + "and s.consumedAt is null and s.expiresAt > :now")
  int consume(
      @Param("stateHash") String stateHash,
      @Param("bindingHash") String bindingHash,
      @Param("now") Instant now);
}
