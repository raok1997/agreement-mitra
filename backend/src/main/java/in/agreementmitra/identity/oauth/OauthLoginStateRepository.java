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
   * race, {@code 0} means the state was unknown, already consumed, or expired. Doing the guard in
   * one conditional UPDATE (not read-then-write) makes the single-use guarantee race-safe.
   */
  @Modifying
  @Query(
      "update OauthLoginState s set s.consumedAt = :now "
          + "where s.stateHash = :stateHash and s.consumedAt is null and s.expiresAt > :now")
  int consume(@Param("stateHash") String stateHash, @Param("now") Instant now);
}
