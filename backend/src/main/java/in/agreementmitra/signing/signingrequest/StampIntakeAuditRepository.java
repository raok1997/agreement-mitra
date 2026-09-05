package in.agreementmitra.signing.signingrequest;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link StampIntakeAudit}. Package-private - Modulith-internal. */
interface StampIntakeAuditRepository extends JpaRepository<StampIntakeAudit, UUID> {}
