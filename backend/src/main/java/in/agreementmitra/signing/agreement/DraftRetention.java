package in.agreementmitra.signing.agreement;

import in.agreementmitra.AgreementIds;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.StoredObject;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The daily retention run (stale-draft-purge): purges unpaid drafts whose content has gone {@link
 * #RETENTION} without an edit, then sweeps orphaned draft-stage objects a failed delete left
 * behind.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}: each {@link DraftService#purgeIfStale} call
 * commits on its own, so one failure leaves the others, and its after-commit object removal fires
 * at once. No log line here carries a throwable or an exception message - Hibernate and Postgres
 * messages repeat full key values, and an unclaimed draft's id is its bearer link - only the
 * exception's class name and ids through {@link AgreementIds#redact}.
 */
@Service
class DraftRetention {

  /**
   * How long an unpaid draft may go without a content edit before it is purged. Terms of service
   * section 10 states this period; a code constant, not a property, so no environment can disagree
   * with the published terms. Pinned by {@code DraftRetentionTest} and the frontend terms test.
   */
  static final Duration RETENTION = Duration.ofDays(90);

  /**
   * An orphaned object younger than this is left alone: the deletion record and the missing row are
   * already committed facts, so this guards an upload that lands as the delete commits.
   */
  static final Duration ORPHAN_GRACE = Duration.ofHours(24);

  static final int PAGE_SIZE = 100;
  static final int MAX_ATTEMPTS = 10_000;
  static final int MAX_OBJECT_REMOVALS = 1_000;

  private static final Instant CURSOR_START_EDITED_AT = Instant.EPOCH;
  private static final UUID CURSOR_START_ID = new UUID(0, 0);

  private static final Logger log = LoggerFactory.getLogger(DraftRetention.class);

  private final AgreementRepository agreements;
  private final AgreementDeletionRepository deletions;
  private final DraftService drafts;
  private final BlobStore blobStore;

  DraftRetention(
      AgreementRepository agreements,
      AgreementDeletionRepository deletions,
      DraftService drafts,
      BlobStore blobStore) {
    this.agreements = agreements;
    this.deletions = deletions;
    this.drafts = drafts;
    this.blobStore = blobStore;
  }

  /**
   * @param failed candidates whose purge threw
   * @param aborted the run stopped early because a candidate page could not be read; the counts are
   *     what was done before that
   */
  record PurgeCounts(int purged, int skipped, int failed, boolean aborted) {}

  record Result(PurgeCounts purge, int objectsRemoved, boolean sweepFailed) {}

  /** Purge, then sweep, each contained; one INFO line of counts. */
  Result run(Instant now) {
    PurgeCounts purge;
    try {
      purge = purge(now);
    } catch (RuntimeException e) {
      // A failed page is handled inside purge() with its partial counts; this is anything else.
      log.warn("Draft retention purge failed ({})", e.getClass().getSimpleName());
      purge = new PurgeCounts(0, 0, 0, true);
    }
    int objectsRemoved = 0;
    boolean sweepFailed = false;
    try {
      objectsRemoved = sweep(now);
    } catch (RuntimeException e) {
      sweepFailed = true;
      log.warn("Draft object sweep failed ({})", e.getClass().getSimpleName());
    }
    log.info(
        "Draft retention run: purged={} skipped={} failed={} purgeAborted={} objectsRemoved={}"
            + " sweepFailed={}",
        purge.purged(),
        purge.skipped(),
        purge.failed(),
        purge.aborted(),
        objectsRemoved,
        sweepFailed);
    return new Result(purge, objectsRemoved, sweepFailed);
  }

  /**
   * Purge every candidate last edited before {@code now - RETENTION}. Keyset-paginated: the cursor
   * moves past every returned row whatever its outcome, so a failing or skipped row is attempted
   * once per run and never stalls later ones. Stops on an empty page or after {@link
   * #MAX_ATTEMPTS}.
   */
  PurgeCounts purge(Instant now) {
    Instant cutoff = now.minus(RETENTION);
    Instant afterEditedAt = CURSOR_START_EDITED_AT;
    UUID afterId = CURSOR_START_ID;
    int attempted = 0;
    int purged = 0;
    int skipped = 0;
    int failed = 0;
    while (attempted < MAX_ATTEMPTS) {
      List<Object[]> page;
      try {
        page =
            agreements.findStaleDraftCandidates(
                cutoff, afterEditedAt, afterId, Math.min(PAGE_SIZE, MAX_ATTEMPTS - attempted));
      } catch (RuntimeException e) {
        log.warn("Draft retention candidate page failed ({})", e.getClass().getSimpleName());
        return new PurgeCounts(purged, skipped, failed, true);
      }
      if (page.isEmpty()) {
        break;
      }
      for (Object[] row : page) {
        UUID id = (UUID) row[0];
        afterId = id;
        afterEditedAt = toInstant(row[1]);
        attempted++;
        try {
          if (drafts.purgeIfStale(id, cutoff)) {
            purged++;
          } else {
            skipped++;
          }
        } catch (RuntimeException e) {
          failed++;
          log.warn(
              "Draft purge failed for agreement {} ({})",
              AgreementIds.redact(id),
              e.getClass().getSimpleName());
        }
      }
    }
    return new PurgeCounts(purged, skipped, failed, false);
  }

  /**
   * Remove draft-stage objects a failed delete left behind (D6), on positive evidence only: the key
   * is exactly a draft-stage key, the object is older than {@link #ORPHAN_GRACE}, no agreement has
   * that id, <b>and</b> a deletion record for that id exists - so an app pointed at an empty or
   * wrong database, or a bucket shared across environments, removes nothing. Removes the exact
   * listed key, at most {@link #MAX_OBJECT_REMOVALS} per run. A failed listing propagates; a failed
   * removal is counted out and the sweep goes on.
   *
   * @return objects removed
   */
  int sweep(Instant now) {
    Instant graceCutoff = now.minus(ORPHAN_GRACE);
    int removed = 0;
    for (String prefix : DraftService.draftStagePrefixes()) {
      for (StoredObject object : blobStore.list(prefix)) {
        if (removed >= MAX_OBJECT_REMOVALS) {
          return removed;
        }
        if (!object.lastModified().isBefore(graceCutoff)) {
          continue;
        }
        Optional<UUID> id = DraftService.draftIdOf(object.key());
        if (id.isEmpty() || agreements.existsById(id.get()) || !deletions.existsById(id.get())) {
          continue;
        }
        try {
          blobStore.delete(object.key());
          removed++;
        } catch (RuntimeException e) {
          log.warn(
              "Orphaned draft object removal failed for agreement {} ({})",
              AgreementIds.redact(id.get()),
              e.getClass().getSimpleName());
        }
      }
    }
    return removed;
  }

  /** A native {@code timestamptz} column arrives as whichever type the driver/Hibernate chose. */
  private static Instant toInstant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof OffsetDateTime offset) {
      return offset.toInstant();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    throw new IllegalStateException(
        "Unexpected last_edited_at type " + value.getClass().getSimpleName());
  }
}
