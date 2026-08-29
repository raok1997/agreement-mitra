package in.agreementmitra.signing.signingrequest;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the stamp-intake audit row.
 *
 * <p><b>{@code REQUIRES_NEW} is load-bearing, not decoration.</b> The most important attempts to
 * audit are the ones that fail - and a duplicate certificate number fails by rolling back the
 * attaching transaction. An audit row written inside that transaction would roll back with it,
 * leaving no trace of exactly the event most worth tracing. A suspended, independently-committed
 * transaction survives the caller's rollback.
 *
 * <p>The audit row is bounded to non-sensitive values: identities, the outcome, the timestamp, and
 * the submitted reference truncated to the column width (a staff-typed value that may be garbage).
 * No certificate number, no scan bytes, no metadata.
 */
@Component
class StampIntakeAuditor {

  private static final Logger log = LoggerFactory.getLogger(StampIntakeAuditor.class);

  /**
   * Matches the column width; a staff-typed reference longer than this is truncated, not rejected.
   */
  private static final int MAX_REFERENCE_LENGTH = 32;

  private final StampIntakeAuditRepository repository;

  StampIntakeAuditor(StampIntakeAuditRepository repository) {
    this.repository = repository;
  }

  /**
   * Record one attempt. Never throws: an audit-write failure must not convert an otherwise-correct
   * 200 (or an otherwise-correct 409) into a 500 for the operator. It is logged instead.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void record(UUID staffIdentityId, UUID agreementId, String submittedReference, String outcome) {
    try {
      repository.save(
          StampIntakeAudit.record(
              staffIdentityId, agreementId, truncate(submittedReference), outcome));
    } catch (RuntimeException e) {
      // No submitted value is logged - only the outcome token, which is a fixed constant.
      log.warn("Stamp-intake audit row could not be written for outcome {}", outcome);
    }
  }

  private static String truncate(String reference) {
    if (reference == null) {
      return null;
    }
    return reference.length() <= MAX_REFERENCE_LENGTH
        ? reference
        : reference.substring(0, MAX_REFERENCE_LENGTH);
  }
}
