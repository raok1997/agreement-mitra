package in.agreementmitra.signing.staffalert;

import java.time.Duration;

/**
 * The retry bounds of a staff alert, and the only place the backoff rule exists. Constants, not
 * properties: the shortest backoff is also the claim's lease, so it must stay longer than one send
 * can take ({@link DiscordStaffNotifier#CONNECT_TIMEOUT} + {@link
 * DiscordStaffNotifier#READ_TIMEOUT}), and configuration must not be able to break that.
 */
final class StaffAlertBackoff {

  static final Duration INITIAL = Duration.ofSeconds(30);
  static final Duration MAX = Duration.ofHours(1);

  /** Sends per alert. The waits between them add up to about three hours. */
  static final int MAX_ATTEMPTS = 10;

  /** Rows one sweep dispatches. */
  static final int BATCH_SIZE = 20;

  /**
   * How far back a paid order is looked for, and how long an alert may stay pending before it is
   * failed unsent. The second use is the rollback guard: it fires only after the feature was
   * switched off or the sweep was down, never as a retry bound.
   */
  static final Duration WINDOW = Duration.ofHours(24);

  private StaffAlertBackoff() {}

  /** The wait after attempt number {@code attempt} (1-based): 30s doubling, capped at one hour. */
  static Duration after(int attempt) {
    Duration wait = INITIAL;
    for (int i = 1; i < attempt && wait.compareTo(MAX) < 0; i++) {
      wait = wait.multipliedBy(2);
    }
    return wait.compareTo(MAX) > 0 ? MAX : wait;
  }
}
