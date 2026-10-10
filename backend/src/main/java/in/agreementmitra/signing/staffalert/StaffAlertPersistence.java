package in.agreementmitra.signing.staffalert;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database steps of the staff alert sweep, each its own short transaction - the same split
 * {@code DeliveryPersistence} makes for delivery. A separate bean because {@link
 * StaffAlertDispatcher} is never transactional: the send happens between {@link #claim} and the
 * outcome step with no transaction open, and a {@code @Transactional} method on the dispatcher
 * itself would be defeated by self-invocation.
 */
@Component
class StaffAlertPersistence {

  private final StaffAlertRepository repository;

  StaffAlertPersistence(StaffAlertRepository repository) {
    this.repository = repository;
  }

  /**
   * Record a pending alert, due now, for each agreement that has none. <b>Inserts only</b>: each
   * insert takes a key-share lock on its agreement row through the foreign key, so the caller reads
   * the paid orders before this transaction, not inside it.
   */
  @Transactional
  void enqueue(Collection<UUID> agreementIds, Instant now) {
    for (UUID agreementId : agreementIds) {
      repository.insertPendingIfAbsent(agreementId, 0, now, now);
    }
  }

  @Transactional(readOnly = true)
  List<UUID> dueAgreementIds(Instant now, int limit) {
    return repository.findDueAgreementIds(now, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  Optional<StaffAlert> find(UUID agreementId) {
    return repository.findById(agreementId);
  }

  /** {@code true} when this caller now owns attempt {@code seen + 1}. */
  @Transactional
  boolean claim(UUID agreementId, int seen, Instant now, Instant nextAttemptAt) {
    return repository.claim(agreementId, seen, seen + 1, now, nextAttemptAt) == 1;
  }

  @Transactional
  boolean markSent(UUID agreementId, int attempts, Instant sentAt) {
    return repository.markSent(agreementId, attempts, sentAt) == 1;
  }

  @Transactional
  boolean markFailed(UUID agreementId, int attempts) {
    return repository.markFailed(agreementId, attempts) == 1;
  }
}
