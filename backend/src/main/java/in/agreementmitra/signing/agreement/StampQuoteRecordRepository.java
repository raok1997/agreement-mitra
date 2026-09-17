package in.agreementmitra.signing.agreement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Frozen stamp quotes. Insert and read only -- rows are never updated. */
public interface StampQuoteRecordRepository extends JpaRepository<StampQuoteRecord, UUID> {

  /** The most recently frozen quote for an agreement, if any order was ever placed with one. */
  Optional<StampQuoteRecord> findTopByAgreementIdOrderByCreatedAtDesc(UUID agreementId);

  /** Frozen quotes for a set of agreements, for the staff queue projection. */
  List<StampQuoteRecord> findByAgreementIdIn(Collection<UUID> agreementIds);
}
