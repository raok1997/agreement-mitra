package in.agreementmitra.signing.staffalert;

import in.agreementmitra.signing.SurplusPayment;
import in.agreementmitra.signing.UnrecordedPayment;
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
   * Record a pending alert, due now, for each paid agreement and each surplus payment order that
   * has none: a paid-order alert keyed by the agreement, a duplicate-payment alert keyed by the
   * surplus order. <b>Inserts only</b>: each insert takes a key-share lock on its agreement row
   * through the foreign key, so the caller reads the paid orders before this transaction, not
   * inside it.
   */
  @Transactional
  void enqueue(Collection<UUID> paidAgreementIds, Collection<SurplusPayment> surplus, Instant now) {
    for (UUID agreementId : paidAgreementIds) {
      repository.insertPendingIfAbsent(
          agreementId, StaffAlertKind.ORDER_PAID.name(), agreementId, 0, now, now);
    }
    for (SurplusPayment payment : surplus) {
      repository.insertPendingIfAbsent(
          payment.paymentOrderId(),
          StaffAlertKind.DUPLICATE_PAYMENT.name(),
          payment.agreementId(),
          0,
          now,
          now);
    }
  }

  /**
   * Record a pending alert, due now, for each unrecorded payment that has none of its kind, keyed
   * by the payment order. <b>Its own transaction</b>, apart from {@link #enqueue}: a refused insert
   * here rolls back no paid-order or duplicate-payment alert.
   */
  @Transactional
  void enqueueUnrecorded(Collection<UnrecordedPayment> unrecorded, Instant now) {
    for (UnrecordedPayment payment : unrecorded) {
      repository.insertPendingIfAbsent(
          payment.paymentOrderId(),
          StaffAlertKind.of(payment.reason()).name(),
          payment.agreementId(),
          0,
          now,
          now);
    }
  }

  @Transactional(readOnly = true)
  List<StaffAlertKey> dueIds(Instant now, int limit) {
    return repository.findDueIds(now, PageRequest.of(0, limit));
  }

  @Transactional(readOnly = true)
  Optional<StaffAlert> find(StaffAlertKey key) {
    return repository.findById(key);
  }

  /** {@code true} when this caller now owns attempt {@code seen + 1}. */
  @Transactional
  boolean claim(StaffAlertKey key, int seen, Instant now, Instant nextAttemptAt) {
    return repository.claim(key.id(), key.kind(), seen, seen + 1, now, nextAttemptAt) == 1;
  }

  @Transactional
  boolean markSent(StaffAlertKey key, int attempts, Instant sentAt) {
    return repository.markSent(key.id(), key.kind(), attempts, sentAt) == 1;
  }

  @Transactional
  boolean markFailed(StaffAlertKey key, int attempts) {
    return repository.markFailed(key.id(), key.kind(), attempts) == 1;
  }
}
