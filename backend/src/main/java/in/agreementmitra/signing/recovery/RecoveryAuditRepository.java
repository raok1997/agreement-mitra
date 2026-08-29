package in.agreementmitra.signing.recovery;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link RecoveryAudit}. Write-mostly: nothing in the app reads it back. */
interface RecoveryAuditRepository extends JpaRepository<RecoveryAudit, UUID> {}
