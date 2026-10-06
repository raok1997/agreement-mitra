package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.BlobStore;
import in.agreementmitra.signing.StoredObject;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.LogCapture;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TemplateCatalogFixture;
import in.agreementmitra.support.TestPdfs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The retention purge and the orphaned-object sweep (stale-draft-purge 4.4-4.7, 4.9) against real
 * Postgres + MinIO. Staleness is set by JDBC on {@code last_edited_at}. {@link BlobStore} and
 * {@link DraftService} are spies keeping real behaviour, so single cases can make storage or one
 * candidate fail. The database and bucket are shared with the rest of the suite, so every assertion
 * is about this test's own fixtures - never a global count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DraftRetentionIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;
  @Autowired private AgreementRepository agreements;
  @Autowired private DraftRetention retention;
  @Autowired private ApplicationContext context;

  @MockitoSpyBean private BlobStore blobStore;
  @MockitoSpyBean private DraftService draftService;

  @RegisterExtension
  final LogCapture retentionLogs = LogCapture.of(DraftRetention.class, Level.DEBUG);

  @RegisterExtension final LogCapture serviceLogs = LogCapture.of(DraftService.class, Level.DEBUG);

  private HttpHeaders owner;

  @BeforeEach
  void seed() {
    TemplateCatalogFixture.seedEligible(jdbc);
    owner = customer("retention-owner-" + UUID.randomUUID());
  }

  // ---- helpers ----

  private HttpHeaders customer(String subject) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(
        HttpHeaders.COOKIE,
        SessionCookie.header(
            StaffSessions.customerSession(
                identityService, handoffService, sessionService, subject)));
    return headers;
  }

  private static Map<String, Object> party(String first, String role) {
    return Map.of(
        "firstName",
        first,
        "lastName",
        "Party",
        "fatherName",
        "Father " + first,
        "currentAddress",
        "1 A St",
        "email",
        first.toLowerCase() + "@example.com",
        "role",
        role);
  }

  private static Map<String, Object> terms(String rent) {
    return Map.of(
        "state",
        TemplateCatalogFixture.ELIGIBLE_STATE,
        "type",
        TemplateCatalogFixture.TYPE,
        "propertyAddress",
        "12 MG Road, Hyderabad",
        "monthlyRent",
        rent,
        "securityDeposit",
        "50000.00",
        "startDate",
        "2026-01-01",
        "endDate",
        "2026-12-01",
        "signers",
        List.of(party("Asha", "OWNER"), party("Tara", "TENANT")));
  }

  private UUID createUnowned() {
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", terms("25000.00"), AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().id();
  }

  private UUID createOwned() {
    UUID id = createUnowned();
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(owner),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return id;
  }

  private void editTerms(UUID id) {
    HttpHeaders headers = new HttpHeaders();
    headers.addAll(owner);
    headers.setContentType(MediaType.APPLICATION_JSON);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id,
                    HttpMethod.PUT,
                    new HttpEntity<>(terms("26000.00"), headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private void lastEditedDaysAgo(UUID id, int days) {
    jdbc.update(
        "UPDATE agreement SET last_edited_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(days))),
        id);
  }

  private static String draftKey(UUID id) {
    return "drafts/" + id + ".pdf";
  }

  private void storePdf(UUID id) {
    blobStore.put(draftKey(id), TestPdfs.singlePage(), "application/pdf");
  }

  private boolean objectExists(String key) {
    try {
      blobStore.get(key);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
  }

  private boolean agreementExists(UUID id) {
    return count("agreement", "id", id) == 1;
  }

  private UUID ownerOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT owner_identity_id FROM agreement WHERE id = ?", UUID.class, id);
  }

  private String referenceOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, id);
  }

  private void seedSigningRequest(UUID id) {
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at)"
            + " VALUES (?, ?, 'PDF_GENERATED', 0, now())",
        UUID.randomUUID(),
        id);
  }

  private void seedOrder(UUID id) {
    jdbc.update(
        "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,"
            + " amount_minor_units, currency, status, created_at, version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,0)",
        UUID.randomUUID(),
        id,
        "RAZORPAY",
        "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18),
        id.toString(),
        49900L,
        "INR",
        "CREATED",
        Timestamp.from(Instant.now()));
  }

  private void setPaymentState(UUID id, String state) {
    jdbc.update("UPDATE agreement SET payment_state = ? WHERE id = ?", state, id);
  }

  private void recordDeletion(UUID id) {
    jdbc.update(
        "INSERT INTO agreement_deletion (agreement_id, tracking_reference, owner_identity_id,"
            + " deleted_at, reason) VALUES (?, 'AMORPHAN01', NULL, now(), 'RETENTION_PURGE')",
        id);
  }

  private static Instant cutoff() {
    return Instant.now().minus(DraftRetention.RETENTION);
  }

  /** No log line of the run's own loggers carries a full fixture id, a key or a message. */
  private void assertCleanLogs(List<UUID> ids) {
    for (LogCapture capture : List.of(retentionLogs, serviceLogs)) {
      assertThat(capture.throwableMessages()).isEmpty();
      for (String message : capture.messages()) {
        ids.forEach(id -> assertThat(message).doesNotContain(id.toString()));
        assertThat(message).doesNotContain("drafts/").doesNotContain("Key (");
      }
    }
  }

  // ---- 4.4 the purge ----

  @Test
  void anUnclaimedStaleDraftIsPurgedWithItsPartiesPdfAndARetentionRecord() {
    UUID id = createUnowned();
    String reference = referenceOf(id);
    storePdf(id);
    lastEditedDaysAgo(id, 91);

    retention.run(Instant.now());

    assertThat(agreementExists(id)).isFalse();
    assertThat(count("signer", "agreement_id", id)).isZero();
    assertThat(objectExists(draftKey(id))).isFalse();
    Map<String, Object> record =
        jdbc.queryForMap("SELECT * FROM agreement_deletion WHERE agreement_id = ?", id);
    assertThat(record)
        .containsOnlyKeys(
            "agreement_id", "tracking_reference", "owner_identity_id", "deleted_at", "reason")
        .containsEntry("tracking_reference", reference)
        .containsEntry("reason", "RETENTION_PURGE");
    assertThat(record.get("owner_identity_id")).isNull();
    // purgeIfStale ran in its own transaction: objects went after commit, not by the inline
    // fallback
    assertThat(serviceLogs.messages()).noneMatch(m -> m.contains("No transaction synchronization"));
    assertCleanLogs(List.of(id));
  }

  @Test
  void aClaimedStaleDraftIsPurgedAndItsRecordNamesTheOwner() {
    UUID id = createOwned();
    UUID ownerId = ownerOf(id);
    lastEditedDaysAgo(id, 91);

    retention.run(Instant.now());

    assertThat(agreementExists(id)).isFalse();
    assertThat(
            rest.exchange("/api/agreements", HttpMethod.GET, new HttpEntity<>(owner), String.class)
                .getBody())
        .doesNotContain(id.toString());
    assertThat(jdbc.queryForMap("SELECT * FROM agreement_deletion WHERE agreement_id = ?", id))
        .containsEntry("owner_identity_id", ownerId)
        .containsEntry("reason", "RETENTION_PURGE");
  }

  @Test
  void onlyUnpaidDraftsPastThePeriodAreCandidatesAndEveryCandidatePassesTheLockTimeCheck() {
    UUID unclaimed = createUnowned();
    UUID claimed = createOwned();
    UUID recent = createUnowned();
    UUID finalised = createOwned();
    UUID ordered = createOwned();
    UUID paid = createOwned();
    UUID waived = createOwned();
    storePdf(recent);
    storePdf(finalised);
    jdbc.update(
        "UPDATE agreement SET created_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minus(Duration.ofDays(200))),
        recent);
    lastEditedDaysAgo(recent, 89);
    for (UUID id : List.of(unclaimed, claimed, finalised, ordered, paid, waived)) {
      lastEditedDaysAgo(id, 91);
    }
    seedSigningRequest(finalised);
    seedOrder(ordered);
    setPaymentState(paid, "PAID");
    setPaymentState(waived, "WAIVED");
    Set<UUID> fixtures = Set.of(unclaimed, claimed, recent, finalised, ordered, paid, waived);

    Instant cutoff = cutoff();
    Set<UUID> candidates =
        agreements.findStaleDraftCandidates(cutoff, Instant.EPOCH, new UUID(0, 0), 10_000).stream()
            .map(row -> (UUID) row[0])
            .filter(fixtures::contains)
            .collect(Collectors.toSet());
    assertThat(candidates).containsExactlyInAnyOrder(unclaimed, claimed);

    retention.run(Instant.now());

    // query ⊆ rule: every candidate the query returned was purged by the lock-time check
    candidates.forEach(id -> assertThat(agreementExists(id)).as(id.toString()).isFalse());
    for (UUID kept : List.of(recent, finalised, ordered, paid, waived)) {
      assertThat(agreementExists(kept)).as(kept.toString()).isTrue();
      assertThat(count("signer", "agreement_id", kept)).isEqualTo(2);
      assertThat(count("agreement_deletion", "agreement_id", kept)).isZero();
    }
    assertThat(count("signing_request", "agreement_id", finalised)).isEqualTo(1);
    assertThat(count("payment_order", "agreement_id", ordered)).isEqualTo(1);
    assertThat(objectExists(draftKey(recent))).isTrue();
    assertThat(objectExists(draftKey(finalised))).isTrue();
  }

  @Test
  void anIntakeAuditRowSurvivesThePurgeWithItsLinkCleared() {
    UUID id = createOwned();
    UUID ownerId = ownerOf(id);
    String reference = referenceOf(id);
    UUID auditId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO stamp_intake_audit"
            + " (id, staff_identity_id, agreement_id, submitted_reference, outcome, occurred_at)"
            + " VALUES (?, ?, ?, ?, 'REJECTED_ERROR', now())",
        auditId,
        ownerId, // any identity row satisfies the FK
        id,
        reference);
    lastEditedDaysAgo(id, 91);

    retention.run(Instant.now());

    assertThat(agreementExists(id)).isFalse();
    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT agreement_id, submitted_reference, outcome FROM stamp_intake_audit"
                + " WHERE id = ?",
            auditId);
    assertThat(audit.get("agreement_id")).isNull();
    assertThat(audit.get("submitted_reference")).isEqualTo(reference);
    assertThat(audit.get("outcome")).isEqualTo("REJECTED_ERROR");
  }

  @Test
  void aFailedBlobRemovalDoesNotStopTheSecondPurge() {
    UUID first = createUnowned();
    UUID second = createUnowned();
    storePdf(first);
    storePdf(second);
    lastEditedDaysAgo(first, 92);
    lastEditedDaysAgo(second, 91);
    doThrow(new IllegalStateException("storage down")).when(blobStore).delete(draftKey(first));

    retention.run(Instant.now());

    assertThat(agreementExists(first)).isFalse();
    assertThat(agreementExists(second)).isFalse();
    assertThat(objectExists(draftKey(second))).isFalse();
    assertCleanLogs(List.of(first, second));
  }

  @Test
  void aDatabaseFailureOnOneCandidateIsLoggedByClassNameAndTheNextIsPurged() {
    UUID failing = createUnowned();
    UUID next = createUnowned();
    lastEditedDaysAgo(failing, 95);
    lastEditedDaysAgo(next, 94);
    doThrow(
            new DataIntegrityViolationException(
                "ERROR: violates foreign key; Key (id)=(" + failing + ")"))
        .when(draftService)
        .purgeIfStale(eq(failing), any());

    retention.run(Instant.now());

    assertThat(agreementExists(failing)).isTrue();
    assertThat(agreementExists(next)).isFalse();
    assertThat(retentionLogs.messages())
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("DataIntegrityViolationException")
                    .contains(failing.toString().substring(0, 8)));
    assertCleanLogs(List.of(failing, next));
  }

  @Test
  void storageFailingEveryCallStillPurgesRowsAndReportsTheSweepFailed() {
    UUID id = createUnowned();
    lastEditedDaysAgo(id, 91);
    doThrow(new IllegalStateException("storage down " + draftKey(id)))
        .when(blobStore)
        .delete(any());
    doThrow(new IllegalStateException("storage down " + draftKey(id))).when(blobStore).list(any());

    DraftRetention.Result result = retention.run(Instant.now());

    assertThat(agreementExists(id)).isFalse();
    assertThat(result.sweepFailed()).isTrue();
    assertThat(retentionLogs.messages())
        .anySatisfy(m -> assertThat(m).contains("sweepFailed=true"));
    assertCleanLogs(List.of(id));
  }

  // ---- 4.5 racing ----

  @Test
  void aDraftEditedAfterSelectionIsKept() {
    UUID id = createOwned();
    lastEditedDaysAgo(id, 91);
    Instant cutoff = cutoff();
    editTerms(id); // commits before the purge locks the row

    assertThat(draftService.purgeIfStale(id, cutoff)).isFalse();
    assertThat(agreementExists(id)).isTrue();
    assertThat(count("agreement_deletion", "agreement_id", id)).isZero();
  }

  @Test
  void aDraftFinalisedAfterSelectionIsKept() {
    UUID id = createOwned();
    lastEditedDaysAgo(id, 91);
    Instant cutoff = cutoff();
    seedSigningRequest(id);

    assertThat(draftService.purgeIfStale(id, cutoff)).isFalse();
    assertThat(agreementExists(id)).isTrue();
    assertThat(count("signing_request", "agreement_id", id)).isEqualTo(1);
  }

  @Test
  void aDraftLockedByAnInFlightEditIsSkippedWithoutWaiting() throws Exception {
    UUID id = createUnowned();
    lastEditedDaysAgo(id, 91);
    Instant cutoff = cutoff();
    Timestamp edited = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));

    try (Connection tx = dataSource.getConnection()) {
      tx.setAutoCommit(false);
      // What findByIdForUpdate renders on PostgreSQL, then the edit's own update.
      try (PreparedStatement lock =
              tx.prepareStatement("SELECT id FROM agreement WHERE id = ? FOR NO KEY UPDATE");
          PreparedStatement update =
              tx.prepareStatement("UPDATE agreement SET last_edited_at = ? WHERE id = ?")) {
        lock.setObject(1, id);
        lock.executeQuery().close();
        update.setTimestamp(1, edited);
        update.setObject(2, id);
        update.executeUpdate();
      }

      CompletableFuture<Boolean> purge =
          CompletableFuture.supplyAsync(() -> draftService.purgeIfStale(id, cutoff));
      assertThat(purge.get(10, TimeUnit.SECONDS)).isFalse();

      CompletableFuture<DraftRetention.PurgeCounts> run =
          CompletableFuture.supplyAsync(() -> retention.purge(Instant.now()));
      assertThat(run.get(30, TimeUnit.SECONDS).skipped()).isPositive();

      tx.commit();
    }
    assertThat(agreementExists(id)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT last_edited_at FROM agreement WHERE id = ?", Timestamp.class, id))
        .isEqualTo(edited);
  }

  // ---- 4.6 the orphan sweep ----

  @Test
  void theSweepRemovesOnlyAnOrphanWithADeletionRecord() {
    UUID orphan = UUID.randomUUID();
    UUID unrecorded = UUID.randomUUID();
    UUID live = createOwned();
    UUID paid = createOwned();
    setPaymentState(paid, "PAID");
    UUID odd = UUID.randomUUID();
    recordDeletion(orphan);
    recordDeletion(odd);
    List<String> kept =
        List.of(
            draftKey(unrecorded),
            draftKey(live),
            draftKey(paid),
            "drafts/readme.txt",
            "drafts/" + odd + ".png",
            "drafts/" + odd.toString().toUpperCase() + ".pdf");
    blobStore.put(draftKey(orphan), TestPdfs.singlePage(), "application/pdf");
    kept.forEach(key -> blobStore.put(key, TestPdfs.singlePage(), "application/pdf"));

    // MinIO cannot backdate lastModified, so the run's clock moves past the 24 h grace instead.
    retention.sweep(Instant.now().plus(Duration.ofHours(25)));

    assertThat(objectExists(draftKey(orphan))).isFalse();
    kept.forEach(key -> assertThat(objectExists(key)).as(key).isTrue());
  }

  @Test
  void aJustWrittenOrphanIsKept() {
    UUID orphan = UUID.randomUUID();
    recordDeletion(orphan);
    storePdf(orphan);

    retention.sweep(Instant.now());

    assertThat(objectExists(draftKey(orphan))).isTrue();
  }

  // ---- 4.7 BlobStore.list against real MinIO ----

  @Test
  void listReturnsEveryKeyUnderThePrefixWithItsLastModifiedAndNothingElse() {
    String prefix = "list-" + UUID.randomUUID() + "/";
    String outside = "list-other-" + UUID.randomUUID() + "/c.pdf";
    Instant before = Instant.now().minusSeconds(60);
    blobStore.put(prefix + "a.pdf", new byte[] {1}, "application/pdf");
    blobStore.put(prefix + "sub/b.pdf", new byte[] {2}, "application/pdf");
    blobStore.put(outside, new byte[] {3}, "application/pdf");

    List<StoredObject> listed = blobStore.list(prefix);

    assertThat(listed)
        .extracting(StoredObject::key)
        .containsExactlyInAnyOrder(prefix + "a.pdf", prefix + "sub/b.pdf");
    assertThat(listed)
        .allSatisfy(
            o ->
                assertThat(o.lastModified())
                    .isAfter(before)
                    .isBefore(Instant.now().plusSeconds(60)));
  }

  // ---- 4.9 the job is opt-in ----

  @Test
  void inTheTestProfileTheJobIsAbsentAndTheRunServiceIsPresent() {
    assertThat(context.getBeanNamesForType(DraftRetentionJob.class)).isEmpty();
    assertThat(context.getBeanNamesForType(DraftRetention.class)).hasSize(1);
  }
}
