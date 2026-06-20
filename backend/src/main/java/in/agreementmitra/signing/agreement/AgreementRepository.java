package in.agreementmitra.signing.agreement;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the {@link Agreement} aggregate. Internal to the {@code signing} module — not a
 * named interface, so it is invisible to other modules.
 */
public interface AgreementRepository extends JpaRepository<Agreement, UUID> {}
