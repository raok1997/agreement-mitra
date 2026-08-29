package in.agreementmitra.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the {@link Identity} aggregate. Package-private -- Modulith-internal. */
interface IdentityRepository extends JpaRepository<Identity, UUID> {}
