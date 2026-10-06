package in.agreementmitra.signing.recovery;

import in.agreementmitra.ClientSource;
import in.agreementmitra.SecurityEvents;
import in.agreementmitra.SlidingWindowRateLimiter;
import in.agreementmitra.SlidingWindowRateLimiter.Decision;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.api.AgreementResponse;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Handles a request to have the recovery link re-sent, from a tracking reference.
 *
 * <p><b>The caller learns nothing.</b> Every path through this class ends the same way from the
 * requester's point of view - unknown reference, unpaid agreement, already claimed, nobody
 * contactable, throttled, or sent. The distinctions are real and are recorded in the audit trail,
 * but they never surface in a response. That is what lets a ~40-bit reference be a selector without
 * becoming a credential (design D1): guessing gains an attacker nothing, because any mail produced
 * goes to the legitimate parties rather than to them.
 *
 * <p>Consequently this method has no meaningful return value and throws nothing a caller could
 * branch on. Resist the temptation to add one.
 */
@Service
public class RecoveryService {

  private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

  /** The route label recovery's lockout events carry: the mapping, never a URI. */
  private static final String ROUTE = "/api/agreements/recovery";

  /** The limiter class recovery's budgets and lockouts are scoped to. */
  private static final String LIMITER_CLASS = "recovery";

  private final AgreementService agreementService;
  private final RecoveryDeliveryService delivery;
  private final SlidingWindowRateLimiter rateLimiter;
  private final SecurityEvents securityEvents;
  private final RecoveryAuditRepository audit;

  RecoveryService(
      AgreementService agreementService,
      RecoveryDeliveryService delivery,
      @Qualifier(RecoveryLimiterConfig.LIMITER) SlidingWindowRateLimiter rateLimiter,
      SecurityEvents securityEvents,
      RecoveryAuditRepository audit) {
    this.agreementService = agreementService;
    this.delivery = delivery;
    this.rateLimiter = rateLimiter;
    this.securityEvents = securityEvents;
    this.audit = audit;
  }

  /**
   * Act on a recovery request. Never throws, and never reports what happened.
   *
   * @param rawReference the reference as submitted
   * @param source the requester, as resolved from the trusted proxy chain, for rate limiting and
   *     forensics
   */
  public void requestRecovery(String rawReference, ClientSource source) {
    Instant now = Instant.now();
    String reference =
        rawReference == null ? "" : rawReference.trim().toUpperCase(java.util.Locale.ROOT);
    String requesterFingerprint = source.key();

    try {
      if (!admitted(source, reference)) {
        record(reference, null, RecoveryOutcome.THROTTLED, 0, requesterFingerprint, now);
        return;
      }

      Optional<AgreementResponse> recoverable =
          agreementService.findRecoverableByTrackingReference(reference);
      if (recoverable.isEmpty()) {
        // Unknown, unpaid, or already claimed - all one outcome here, because the caller must not
        // be able to tell them apart and an operator can reconstruct which from the agreement.
        record(reference, null, RecoveryOutcome.NOT_ELIGIBLE, 0, requesterFingerprint, now);
        return;
      }

      AgreementResponse agreement = recoverable.get();
      int sent = delivery.sendRecoveryLink(agreement);
      RecoveryOutcome outcome = sent > 0 ? RecoveryOutcome.SENT : RecoveryOutcome.NO_CONTACT;
      record(reference, agreement.id(), outcome, sent, requesterFingerprint, now);
    } catch (RuntimeException failed) {
      // A failure here must still look like every other outcome to the caller. Recorded, not
      // raised.
      log.warn("Recovery request could not be completed");
      record(reference, null, RecoveryOutcome.NOT_CONFIGURED, 0, requesterFingerprint, now);
    }
  }

  /**
   * A reference refusal still counts against the source, so tripping a reference refunds no source
   * budget; a source refusal is not counted against the reference. A lockout is logged once, when
   * it begins; the reference only as a keyed digest.
   */
  private boolean admitted(ClientSource source, String reference) {
    Decision decision =
        rateLimiter.tryAcquire(
            LIMITER_CLASS,
            source.key(),
            RecoveryLimiterConfig.SOURCE,
            reference,
            RecoveryLimiterConfig.REFERENCE);
    if (decision.sourceLockout() > 0) {
      securityEvents.lockout(ROUTE, LIMITER_CLASS, source, decision.sourceLockout());
    }
    if (decision.resourceLockout() > 0) {
      securityEvents.resourceLockout(
          ROUTE, LIMITER_CLASS, source, reference, decision.resourceLockout());
    }
    return decision.allowed();
  }

  /**
   * Audit is best-effort in the sense that failing to write it must not change the response - but a
   * failure here is worth a loud log, because it means abuse would go unrecorded.
   */
  private void record(
      String reference,
      java.util.UUID agreementId,
      RecoveryOutcome outcome,
      int sent,
      String fingerprint,
      Instant now) {
    try {
      audit.save(RecoveryAudit.of(reference, agreementId, outcome, sent, null, fingerprint, now));
    } catch (RuntimeException failed) {
      log.error("Recovery audit could not be written; abuse of this endpoint would be unrecorded");
    }
  }
}
