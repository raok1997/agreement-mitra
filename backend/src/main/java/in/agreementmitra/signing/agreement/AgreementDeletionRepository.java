package in.agreementmitra.signing.agreement;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link AgreementDeletion}. Package-private - Modulith-internal. */
interface AgreementDeletionRepository extends JpaRepository<AgreementDeletion, UUID> {}
