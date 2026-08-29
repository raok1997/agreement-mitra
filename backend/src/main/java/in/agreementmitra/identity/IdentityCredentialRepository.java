package in.agreementmitra.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link IdentityCredential}. Package-private -- Modulith-internal. The lookup by
 * {@code (provider, providerSubject)} drives find-or-create; the DB uniqueness constraint on the
 * same pair is the race-safe backstop.
 */
interface IdentityCredentialRepository extends JpaRepository<IdentityCredential, UUID> {

  Optional<IdentityCredential> findByProviderAndProviderSubject(
      String provider, String providerSubject);
}
