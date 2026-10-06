package in.agreementmitra.identity.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link LoginHandoff}. Package-private -- Modulith-internal. */
interface LoginHandoffRepository extends JpaRepository<LoginHandoff, UUID> {

  Optional<LoginHandoff> findByHandoffHash(String handoffHash);

  /**
   * Atomically consume a handoff: mark it consumed only if currently unconsumed and unexpired.
   * Returns {@code 1} when this caller won the single-use race, {@code 0} when the handoff was
   * unknown, already exchanged, expired, or bound to another browser -- so none of those mints a
   * session. A row with a null binding hash never matches (login-browser-binding D2).
   */
  @Modifying
  @Query(
      "update LoginHandoff h set h.consumedAt = :now "
          + "where h.handoffHash = :handoffHash and h.browserBindingHash = :bindingHash "
          + "and h.consumedAt is null and h.expiresAt > :now")
  int consume(
      @Param("handoffHash") String handoffHash,
      @Param("bindingHash") String bindingHash,
      @Param("now") Instant now);
}
