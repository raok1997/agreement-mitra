package in.agreementmitra.signing.delivery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Delivery retry tuning, bound from {@code signing.delivery.*}. Everything is bounded: attempts
 * stop at {@code maxAttempts}, backoff doubles from {@code initialBackoff} up to {@code
 * maxBackoff}, and the sweep touches at most {@code batchSize} rows per run.
 *
 * <p>A bound matters more here than it looks. Without one, a permanently broken address would be
 * retried forever and nobody would ever look at it; with one, the row lands in {@code FAILED} with
 * a reason and appears on the staff view, which is the outcome that actually gets the document to
 * the party.
 *
 * @param retryEnabled whether the scheduled retry sweep runs (false in the test profile, where the
 *     job is invoked directly)
 * @param interval how often the sweep runs
 * @param maxAttempts total attempts per recipient before the row is marked failed
 * @param initialBackoff delay before the second attempt; doubles each time
 * @param maxBackoff ceiling on the doubling
 * @param batchSize maximum rows processed per sweep
 */
@ConfigurationProperties(prefix = "signing.delivery")
record DeliveryProperties(
    boolean retryEnabled,
    Duration interval,
    int maxAttempts,
    Duration initialBackoff,
    Duration maxBackoff,
    int batchSize) {

  DeliveryProperties {
    if (interval == null) {
      interval = Duration.ofMinutes(5);
    }
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (initialBackoff == null) {
      initialBackoff = Duration.ofMinutes(1);
    }
    if (maxBackoff == null) {
      maxBackoff = Duration.ofHours(1);
    }
    if (batchSize <= 0) {
      batchSize = 50;
    }
  }

  /**
   * The delay before attempt number {@code attempts + 1}, doubling from {@link #initialBackoff} and
   * capped at {@link #maxBackoff}. Computed rather than stored so the schedule can be retuned by
   * configuration without a migration.
   */
  Duration backoffAfter(int attempts) {
    long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
    Duration delay = initialBackoff.multipliedBy(factor);
    return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
  }
}
