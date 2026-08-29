package in.agreementmitra.identity.session;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link AuthSession}. Package-private -- Modulith-internal. Resolution is by the
 * unique {@code valueHash} (the re-hash of a presented bearer value); revocation deletes by the
 * same hash.
 */
interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

  Optional<AuthSession> findByValueHash(String valueHash);

  @Modifying
  @Query("delete from AuthSession s where s.valueHash = :valueHash")
  int deleteByValueHash(@Param("valueHash") String valueHash);
}
