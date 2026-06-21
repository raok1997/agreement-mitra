package in.agreementmitra.signing.signingrequest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reconciliation-job tuning, bound from {@code signing.reconciliation.*}. All bounded: the job only
 * touches rows older than {@code ageThreshold} (or {@code grace} for failed downloads) and at most
 * {@code batchSize} per run. Internal to the signing module.
 *
 * @param enabled whether the scheduled job runs (false in the test profile)
 * @param ageThreshold minimum age of a {@code SIGN_REQUESTED} row before it is reconciled
 * @param grace minimum age of a {@code SIGNED}-without-artifacts row (avoids racing the webhook)
 * @param batchSize maximum rows processed per run
 */
@ConfigurationProperties(prefix = "signing.reconciliation")
record ReconciliationProperties(
    boolean enabled, Duration ageThreshold, Duration grace, int batchSize) {

  ReconciliationProperties {
    if (ageThreshold == null) {
      ageThreshold = Duration.ofMinutes(15);
    }
    if (grace == null) {
      grace = Duration.ofMinutes(2);
    }
    if (batchSize <= 0) {
      batchSize = 50;
    }
  }
}
