package in.agreementmitra.signing.staffalert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the staff alert sweep every 30 seconds. This job is the <b>only</b> thing that records or
 * sends a staff alert - nothing on the payment confirmation path does - so a worst-case delay of
 * one interval is the whole cost, and it is immaterial against a stamp purchase that takes a person
 * minutes.
 *
 * <p>Disabled in the test profile ({@code staff-alert.dispatch-enabled=false}); behaviour is tested
 * by calling {@link StaffAlertDispatcher} directly.
 */
@Component
@ConditionalOnProperty(
    prefix = "staff-alert",
    name = "dispatch-enabled",
    havingValue = "true",
    matchIfMissing = true)
class StaffAlertDispatchJob {

  private static final Logger log = LoggerFactory.getLogger(StaffAlertDispatchJob.class);

  private final StaffAlertDispatcher dispatcher;

  StaffAlertDispatchJob(StaffAlertDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(
      fixedDelayString = "${staff-alert.interval:PT30S}",
      initialDelayString = "${staff-alert.initial-delay:PT1M}")
  void sweep() {
    try {
      dispatcher.dispatchDue();
    } catch (RuntimeException e) {
      // A sweep failure must never escape into the scheduler. Class only: an exception message
      // here can carry the webhook URL.
      log.warn(
          "Staff alert sweep failed ({}); the next run will try again",
          e.getClass().getSimpleName());
    }
  }
}
