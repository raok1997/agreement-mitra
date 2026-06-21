package in.agreementmitra.signing.signingrequest;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the {@link SigningRequest} aggregate. Package-private — Modulith-internal. */
interface SigningRequestRepository extends JpaRepository<SigningRequest, UUID> {

  /** Look up by the vendor document id (unique). Used by the webhook + reconciliation paths. */
  Optional<SigningRequest> findByProviderDocumentId(String providerDocumentId);
}
