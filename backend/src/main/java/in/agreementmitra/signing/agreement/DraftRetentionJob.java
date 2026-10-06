package in.agreementmitra.signing.agreement;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the daily {@link DraftRetention} run (stale-draft-purge D7).
 *
 * <p><b>Opt-in</b> ({@code matchIfMissing = false}): the run deletes objects in whatever bucket it
 * is pointed at, so a database restored or cloned from production and run against a shared bucket
 * would purge agreements live in production. Production sets {@code
 * SIGNING_DRAFT_RETENTION_ENABLED=true} (docs/DEPLOYMENT.md); everywhere else it stays off and
 * tests invoke {@link DraftRetention#run} directly. Disabling it in production is an incident-only
 * lever, since terms of service section 10 promises the deletion.
 */
@Component
@ConditionalOnProperty(
    prefix = "signing.draft-retention",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false)
class DraftRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(DraftRetentionJob.class);

  private final DraftRetention retention;

  DraftRetentionJob(DraftRetention retention) {
    this.retention = retention;
  }

  @Scheduled(cron = "${signing.draft-retention.cron}", zone = "Asia/Kolkata")
  void run() {
    try {
      retention.run(Instant.now());
    } catch (RuntimeException e) {
      // Must never escape into the scheduler. Class name only - never the message.
      log.warn("Draft retention run failed ({})", e.getClass().getSimpleName());
    }
  }
}
