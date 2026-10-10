package in.agreementmitra.signing.staffalert;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.PaymentOrderQuery;
import in.agreementmitra.signing.SurplusPayment;
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
 * The staff alert sweep: record a paid-order alert for each agreement the gateway has marked paid
 * and a duplicate-payment alert for each surplus payment order, then send the ones that are due.
 * Both kinds share the one claim, lease, backoff and outcome machinery.
 *
 * <p><b>Alerts are derived from paid payment orders, not from the confirmation.</b> The paid order
 * is already the durable record of the trigger, so reading it needs no listener and no write in the
 * confirmation transaction - the alert cannot roll a payment back, and a crash cannot lose one.
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
    for (UUID alertId : dueAlertIds(Instant.now())) {
      try {
        dispatchOne(alertId, Instant.now());
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
   */
  void enqueue(Instant now) {
    if (!notifier.configured()) {
      return;
    }
    Instant cutoff = now.minus(StaffAlertBackoff.WINDOW);
    List<UUID> paid = paymentOrders.agreementsWithOrderPaidSince(cutoff);
    List<SurplusPayment> surplus = paymentOrders.surplusOrdersPaidSince(cutoff);
    if (!paid.isEmpty() || !surplus.isEmpty()) {
      persistence.enqueue(paid, surplus, now);
    }
  }

  List<UUID> dueAlertIds(Instant now) {
    if (!notifier.configured()) {
      return List.of();
    }
    return persistence.dueIds(now, StaffAlertBackoff.BATCH_SIZE);
  }

  /**
   * Send one alert if it is pending and due, and record what happened.
   *
   * @param alertId the alert's key - never logged, since for a paid-order alert it is the agreement
   *     id
   */
  void dispatchOne(UUID alertId, Instant now) {
    if (!notifier.configured()) {
      return;
    }
    Optional<StaffAlert> found = persistence.find(alertId);
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
      if (persistence.markFailed(alertId, seen)) {
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
    if (!persistence.claim(alertId, seen, now, now.plus(StaffAlertBackoff.after(attempt)))) {
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
      persistence.markSent(alertId, attempt, now);
    } else if (failure == Kind.PERMANENT || attempt >= StaffAlertBackoff.MAX_ATTEMPTS) {
      if (persistence.markFailed(alertId, attempt)) {
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
