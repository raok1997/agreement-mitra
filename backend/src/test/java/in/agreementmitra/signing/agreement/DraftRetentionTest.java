package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.StoredObject;
import in.agreementmitra.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DraftRetention} - mocked collaborators, no Spring, no I/O. Covers the
 * keyset loop (one attempt per candidate, cap, empty page), the sweep's positive-evidence rule and
 * cap, containment of a failed sweep, the period pin, and that nothing logged carries an exception
 * message, a full id or an object key.
 */
@ExtendWith(MockitoExtension.class)
class DraftRetentionTest {

  private static final Instant NOW = Instant.parse("2026-10-06T22:00:00Z");
  private static final Instant CUTOFF = NOW.minus(Duration.ofDays(90));

  @Mock private AgreementRepository agreements;
  @Mock private AgreementDeletionRepository deletions;
  @Mock private DraftService drafts;
  @Mock private BlobStore blobStore;

  @RegisterExtension final LogCapture logs = LogCapture.of(DraftRetention.class, Level.DEBUG);

  private DraftRetention retention() {
    return new DraftRetention(agreements, deletions, drafts, blobStore);
  }

  private static Object[] row(UUID id, Instant editedAt) {
    return new Object[] {id, editedAt};
  }

  private void assertLogsCarryNoSecret(List<UUID> ids, List<String> keys) {
    assertThat(logs.throwableMessages()).isEmpty();
    for (String message : logs.messages()) {
      ids.forEach(id -> assertThat(message).doesNotContain(id.toString()));
      keys.forEach(key -> assertThat(message).doesNotContain(key));
      assertThat(message).doesNotContain("secret-detail");
    }
  }

  // --- period ------------------------------------------------------------------------------

  @Test
  void theRetentionPeriodIsNinetyDays() {
    assertThat(DraftRetention.RETENTION)
        .as("terms of service section 10 states 90 days; change both together")
        .isEqualTo(Duration.ofDays(90));
  }

  // --- purge -------------------------------------------------------------------------------

  @Test
  void aFailingCandidateIsAttemptedOnceAndLaterCandidatesArePurged() {
    UUID failing = UUID.randomUUID();
    UUID skipped = UUID.randomUUID();
    UUID purged = UUID.randomUUID();
    Instant t1 = CUTOFF.minusSeconds(300);
    Instant t2 = CUTOFF.minusSeconds(200);
    Instant t3 = CUTOFF.minusSeconds(100);
    when(agreements.findStaleDraftCandidates(
            CUTOFF, Instant.EPOCH, new UUID(0, 0), DraftRetention.PAGE_SIZE))
        .thenReturn(List.of(row(failing, t1), row(skipped, t2), row(purged, t3)));
    when(agreements.findStaleDraftCandidates(CUTOFF, t3, purged, DraftRetention.PAGE_SIZE))
        .thenReturn(List.of());
    when(drafts.purgeIfStale(failing, CUTOFF))
        .thenThrow(new IllegalStateException("secret-detail Key (id)=(" + failing + ")"));
    when(drafts.purgeIfStale(skipped, CUTOFF)).thenReturn(false);
    when(drafts.purgeIfStale(purged, CUTOFF)).thenReturn(true);

    DraftRetention.PurgeCounts counts = retention().purge(NOW);

    assertThat(counts).isEqualTo(new DraftRetention.PurgeCounts(1, 1, 1, false));
    verify(drafts, times(1)).purgeIfStale(failing, CUTOFF);
    assertThat(logs.messages())
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("IllegalStateException")
                    .contains(failing.toString().substring(0, 8)));
    assertLogsCarryNoSecret(List.of(failing, skipped, purged), List.of());
  }

  @Test
  void theLoopEndsOnAnEmptyPage() {
    when(agreements.findStaleDraftCandidates(any(), any(), any(), anyInt())).thenReturn(List.of());

    assertThat(retention().purge(NOW)).isEqualTo(new DraftRetention.PurgeCounts(0, 0, 0, false));
    verify(drafts, never()).purgeIfStale(any(), any());
  }

  @Test
  void theLoopStopsAtTenThousandAttempts() {
    Instant[] clock = {CUTOFF.minus(Duration.ofDays(400))};
    when(agreements.findStaleDraftCandidates(eq(CUTOFF), any(), any(), anyInt()))
        .thenAnswer(
            call -> {
              int limit = call.getArgument(3);
              List<Object[]> page = new ArrayList<>();
              for (int i = 0; i < limit; i++) {
                clock[0] = clock[0].plusMillis(1);
                page.add(row(UUID.randomUUID(), clock[0]));
              }
              return page;
            });
    when(drafts.purgeIfStale(any(), eq(CUTOFF))).thenReturn(true);

    DraftRetention.PurgeCounts counts = retention().purge(NOW);

    assertThat(counts.purged()).isEqualTo(DraftRetention.MAX_ATTEMPTS);
    verify(drafts, times(DraftRetention.MAX_ATTEMPTS)).purgeIfStale(any(), eq(CUTOFF));
  }

  // --- sweep -------------------------------------------------------------------------------

  private static StoredObject object(String key, Duration age) {
    return new StoredObject(key, NOW.minus(age));
  }

  private static String key(UUID id) {
    return "drafts/" + id + ".pdf";
  }

  @Test
  void theSweepRemovesOnlyAnOldOrphanWithADeletionRecord() {
    UUID orphan = UUID.randomUUID();
    UUID young = UUID.randomUUID();
    UUID live = UUID.randomUUID();
    UUID unrecorded = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(blobStore.list("drafts/"))
        .thenReturn(
            List.of(
                object(key(orphan), Duration.ofDays(2)),
                object(key(young), Duration.ofHours(1)),
                object(key(live), Duration.ofDays(30)),
                object(key(unrecorded), Duration.ofDays(2)),
                object("drafts/readme.txt", Duration.ofDays(2)),
                object("drafts/" + other + ".png", Duration.ofDays(2)),
                object("drafts/" + other.toString().toUpperCase() + ".pdf", Duration.ofDays(2))));
    when(agreements.existsById(orphan)).thenReturn(false);
    when(deletions.existsById(orphan)).thenReturn(true);
    when(agreements.existsById(live)).thenReturn(true);
    when(agreements.existsById(unrecorded)).thenReturn(false);
    when(deletions.existsById(unrecorded)).thenReturn(false);

    assertThat(retention().sweep(NOW)).isEqualTo(1);

    verify(blobStore).delete(key(orphan));
    verify(blobStore, times(1)).delete(any());
    verify(agreements, never()).existsById(young);
    verify(deletions, never()).existsById(live);
  }

  @Test
  void theSweepStopsAtOneThousandRemovals() {
    List<StoredObject> listing = new ArrayList<>();
    for (int i = 0; i < DraftRetention.MAX_OBJECT_REMOVALS + 5; i++) {
      listing.add(object(key(UUID.randomUUID()), Duration.ofDays(2)));
    }
    when(blobStore.list("drafts/")).thenReturn(listing);
    lenient().when(agreements.existsById(any())).thenReturn(false);
    lenient().when(deletions.existsById(any())).thenReturn(true);

    assertThat(retention().sweep(NOW)).isEqualTo(DraftRetention.MAX_OBJECT_REMOVALS);
    verify(blobStore, times(DraftRetention.MAX_OBJECT_REMOVALS)).delete(any());
  }

  @Test
  void aFailedRemovalIsCountedOutAndTheSweepGoesOn() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(blobStore.list("drafts/"))
        .thenReturn(
            List.of(
                object(key(first), Duration.ofDays(2)), object(key(second), Duration.ofDays(2))));
    when(agreements.existsById(any())).thenReturn(false);
    when(deletions.existsById(any())).thenReturn(true);
    doThrow(new IllegalStateException("secret-detail " + key(first)))
        .when(blobStore)
        .delete(key(first));

    assertThat(retention().sweep(NOW)).isEqualTo(1);
    verify(blobStore).delete(key(second));
    assertLogsCarryNoSecret(List.of(first, second), List.of(key(first), key(second)));
  }

  // --- run ---------------------------------------------------------------------------------

  @Test
  void aFailedListingLeavesThePurgeCountsAndReportsTheSweepFailed() {
    UUID stale = UUID.randomUUID();
    Instant editedAt = CUTOFF.minusSeconds(10);
    when(agreements.findStaleDraftCandidates(
            CUTOFF, Instant.EPOCH, new UUID(0, 0), DraftRetention.PAGE_SIZE))
        .thenReturn(List.<Object[]>of(row(stale, editedAt)));
    when(agreements.findStaleDraftCandidates(CUTOFF, editedAt, stale, DraftRetention.PAGE_SIZE))
        .thenReturn(List.of());
    when(drafts.purgeIfStale(stale, CUTOFF)).thenReturn(true);
    when(blobStore.list(any())).thenThrow(new IllegalStateException("secret-detail " + key(stale)));

    DraftRetention.Result result = retention().run(NOW);

    assertThat(result.purge()).isEqualTo(new DraftRetention.PurgeCounts(1, 0, 0, false));
    assertThat(result.sweepFailed()).isTrue();
    assertThat(result.objectsRemoved()).isZero();
    assertThat(logs.messages())
        .anySatisfy(m -> assertThat(m).contains("purged=1").contains("sweepFailed=true"));
    assertLogsCarryNoSecret(List.of(stale), List.of(key(stale)));
  }

  @Test
  void aFailedPageKeepsThePartialCountsAndReportsThePurgeAborted() {
    UUID purged = UUID.randomUUID();
    Instant editedAt = CUTOFF.minusSeconds(10);
    when(agreements.findStaleDraftCandidates(
            CUTOFF, Instant.EPOCH, new UUID(0, 0), DraftRetention.PAGE_SIZE))
        .thenReturn(List.<Object[]>of(row(purged, editedAt)));
    when(agreements.findStaleDraftCandidates(CUTOFF, editedAt, purged, DraftRetention.PAGE_SIZE))
        .thenThrow(new IllegalStateException("secret-detail"));
    when(drafts.purgeIfStale(purged, CUTOFF)).thenReturn(true);
    when(blobStore.list("drafts/")).thenReturn(List.of());

    DraftRetention.Result result = retention().run(NOW);

    assertThat(result.purge()).isEqualTo(new DraftRetention.PurgeCounts(1, 0, 0, true));
    assertThat(logs.messages())
        .anySatisfy(m -> assertThat(m).contains("purged=1").contains("purgeAborted=true"));
    assertLogsCarryNoSecret(List.of(purged), List.of());
  }

  @Test
  void aFailedPurgeStillRunsTheSweep() {
    when(agreements.findStaleDraftCandidates(any(), any(), any(), anyInt()))
        .thenThrow(new IllegalStateException("secret-detail"));
    when(blobStore.list("drafts/")).thenReturn(List.of());

    DraftRetention.Result result = retention().run(NOW);

    assertThat(result.purge().aborted()).isTrue();
    assertThat(result.sweepFailed()).isFalse();
    verify(blobStore).list("drafts/");
    assertLogsCarryNoSecret(List.of(), List.of());
  }
}
