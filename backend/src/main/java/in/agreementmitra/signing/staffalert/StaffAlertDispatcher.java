package in.agreementmitra.signing.staffalert;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SurplusPayment;
import in.agreementmitra.signing.UnrecordedPayment;
import in.agreementmitra.signing.agreement.AgreementService;
import in.agreementmitra.signing.agreement.StaffAgreementView;
import in.agreementmitra.signing.contact.DeliveryChannelProperties;
import in.agreementmitra.signing.staffalert.StaffAlertDeliveryException.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The staff alert sweep: record a paid-order alert for each agreement the gateway has marked paid,
 * a duplicate-payment alert for each surplus payment order, and an alert for each payment order
 * whose captured payment was refused or not recorded, then send the ones that are due. Every kind
 * shares the one claim, lease, backoff and outcome machinery.
 *
 * <p><b>Alerts are derived from what is already stored, not from the confirmation.</b> The paid
 * order, and for an unrecorded payment the payment event ledger row, is already the durable record
 * of the trigger, so reading it needs no listener and no write in the confirmation transaction -
 * the alert cannot roll a payment back, and a crash cannot lose one.
 *
 * <p><b>Never transactional.</b> Each database step is a short transaction on {@link
 * StaffAlertPersistence}; the send runs between the claim and the outcome with none open.
 *
 * <p><b>The claim is the lease.</b> Claiming a row counts the attempt and writes its next-attempt
 * time before the send, so a crash mid-send needs no recovery: the row simply comes due again. That
 * deliberately differs from {@code SignedDocumentDeliveryService}, whose {@code IN_PROGRESS} claim
 * has no lease. The price is at-least-once delivery - a send the channel accepted may repeat after
 * a read timeout or a crash - which is the right side to err on for an alert.
 *
 * <p>The steps take the time as a parameter so a test can advance it; {@link #dispatchDue()} reads
 * the clock afresh for each row, because a lease measured from the start of a slow sweep would
 * already have lapsed for the later rows.
 */
@Component
@EnableConfigurationProperties(StaffAlertProperties.class)
class StaffAlertDispatcher {

  private static final Logger log = LoggerFactory.getLogger(StaffAlertDispatcher.class);

  private final StaffNotifier notifier;
  private final PaymentOrderQuery paymentOrders;
  private final StaffAlertPersistence persistence;
  private final AgreementService agreements;
  private final DeliveryChannelProperties channels;

  StaffAlertDispatcher(
      StaffNotifier notifier,
      PaymentOrderQuery paymentOrders,
      StaffAlertPersistence persistence,
      AgreementService agreements,
      DeliveryChannelProperties channels) {
    this.notifier = notifier;
    this.paymentOrders = paymentOrders;
    this.persistence = persistence;
    this.agreements = agreements;
    this.channels = channels;
  }

  /** One full sweep. */
  void dispatchDue() {
    if (!notifier.configured()) {
      return;
    }
    enqueue(Instant.now());
    for (StaffAlertKey alertKey : dueAlertIds(Instant.now())) {
      try {
        dispatchOne(alertKey, Instant.now());
      } catch (RuntimeException e) {
        // One alert's failure must not abort the batch. Class only - never the throwable.
        log.warn("Staff alert dispatch skipped one alert ({})", e.getClass().getSimpleName());
      }
    }
  }

  /**
   * Record a pending alert for each agreement paid, and each surplus order paid, inside the
   * look-back window that has none. The two reads are disjoint: a surplus order raises only the
   * duplicate-payment alert.
   *
   * <p>Before those, the same for each unrecorded payment in the ledger, <b>on its own</b>: its
   * read and its insert cannot stop the two look-backs or the dispatch that follows, and running
   * first, it is not stopped by a fault in them either.
   */
  void enqueue(Instant now) {
    if (!notifier.configured()) {
      return;
    }
    Instant cutoff = now.minus(StaffAlertBackoff.WINDOW);
    try {
      List<UnrecordedPayment> unrecorded = paymentOrders.unrecordedPaymentsSince(cutoff);
      if (!unrecorded.isEmpty()) {
        persistence.enqueueUnrecorded(unrecorded, now);
      }
    } catch (RuntimeException e) {
      // ERROR, not the WARN used below: this fault does not clear on its own, and while it lasts
      // no unrecorded-payment alert is raised at all. Class only - never the throwable.
      log.error(
          "Staff alert look-back of the payment event ledger failed ({}); no unrecorded-payment"
              + " alert is raised until it succeeds",
          e.getClass().getSimpleName());
    }
    List<UUID> paid = paymentOrders.agreementsWithOrderPaidSince(cutoff);
    List<SurplusPayment> surplus = paymentOrders.surplusOrdersPaidSince(cutoff);
    if (!paid.isEmpty() || !surplus.isEmpty()) {
      persistence.enqueue(paid, surplus, now);
    }
  }

  List<StaffAlertKey> dueAlertIds(Instant now) {
    if (!notifier.configured()) {
      return List.of();
    }
    return persistence.dueIds(now, StaffAlertBackoff.BATCH_SIZE);
  }

  /**
   * Send one alert if it is pending and due, and record what happened.
   *
   * @param alertKey the alert's key - never logged, since for a paid-order alert its id is the
   *     agreement id
   */
  void dispatchOne(StaffAlertKey alertKey, Instant now) {
    if (!notifier.configured()) {
      return;
    }
    Optional<StaffAlert> found = persistence.find(alertKey);
    if (found.isEmpty() || found.get().status() != StaffAlertStatus.PENDING) {
      return;
    }
    StaffAlert alert = found.get();
    StaffAlertKind kind = alert.kind();
    UUID agreementId = alert.agreementId();
    int seen = alert.attempts();
    if (alert.nextAttemptAt().isAfter(now)) {
      return;
    }
    if (alert.createdAt().isBefore(now.minus(StaffAlertBackoff.WINDOW))
        || seen >= StaffAlertBackoff.MAX_ATTEMPTS) {
      // Stale, or the last attempt ended without an outcome being recorded (a crash, or a database
      // failure after the send). Failed without another send - which is not proof nothing arrived.
      if (persistence.markFailed(alertKey, seen)) {
        log.error(
            "Staff alert {} for agreement {} closed without a recorded delivery after {}"
                + " attempt(s)",
            kind,
            AgreementIds.redact(agreementId),
            seen);
      }
      return;
    }
    int attempt = seen + 1;
    if (!persistence.claim(alertKey, seen, now, now.plus(StaffAlertBackoff.after(attempt)))) {
      return; // another run holds it
    }

    Kind failure;
    int status = StaffAlertDeliveryException.NO_STATUS;
    try {
      notifier.send(compose(agreementId, kind));
      failure = null;
    } catch (StaffAlertDeliveryException e) {
      failure = e.kind();
      status = e.status();
    } catch (RuntimeException e) {
      // Anything unexpected while composing or sending is worth another attempt. Class only.
      log.warn(
          "Staff alert {} for agreement {} attempt failed unexpectedly ({})",
          kind,
          AgreementIds.redact(agreementId),
          e.getClass().getSimpleName());
      failure = Kind.TRANSIENT;
    }

    if (failure == null) {
      // Outside the try: a failure to RECORD a delivered alert must never be read as a failure to
      // send it. If this throws, the row stays pending and is resent - a duplicate, never a
      // delivered alert marked failed.
      persistence.markSent(alertKey, attempt, now);
    } else if (failure == Kind.PERMANENT || attempt >= StaffAlertBackoff.MAX_ATTEMPTS) {
      if (persistence.markFailed(alertKey, attempt)) {
        log.error(
            "Staff alert {} for agreement {} failed and will not be retried ({}, status {},"
                + " attempt {})",
            kind,
            AgreementIds.redact(agreementId),
            failure,
            status,
            attempt);
      }
    } else {
      // The claim already scheduled the retry; nothing to write.
      log.warn(
          "Staff alert {} for agreement {} not delivered (status {}, attempt {}); it will be"
              + " retried",
          kind,
          AgreementIds.redact(agreementId),
          status,
          attempt);
    }
  }

  private StaffAlertMessage compose(UUID agreementId, StaffAlertKind kind) {
    StaffAgreementView view =
        agreements.staffViewsByAgreementId(List.of(agreementId)).get(agreementId);
    if (view == null || view.trackingReference() == null) {
      throw new StaffAlertDeliveryException(Kind.PERMANENT, StaffAlertDeliveryException.NO_STATUS);
    }
    return StaffAlertMessages.from(kind, view, channels.publicBaseUrl());
  }
}
