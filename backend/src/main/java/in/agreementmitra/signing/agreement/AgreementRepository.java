package in.agreementmitra.signing.agreement;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the {@link Agreement} aggregate. Package-private — Modulith-internal. */
interface AgreementRepository extends JpaRepository<Agreement, UUID> {}
