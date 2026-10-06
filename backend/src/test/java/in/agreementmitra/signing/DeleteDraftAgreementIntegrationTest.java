package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.RawClient;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TemplateCatalogFixture;
import in.agreementmitra.support.TestPdfs;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The owner's delete of an unpaid draft (delete-draft-agreement 4.4-4.8). Real Postgres + MinIO +
 * the real security chain. {@link BlobStore} is a spy keeping real behaviour, so one case can make
 * the object removal fail. Refusal fixtures are written by JDBC so each clause of the rule is
 * isolated from every other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DeleteDraftAgreementIntegrationTest {

  private static final String NOT_DELETABLE = "urn:agreementmitra:problem:draft-not-deletable";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper json;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;
  @LocalServerPort private int port;

  @MockitoSpyBean private BlobStore blobStore;

  private HttpHeaders owner;

  @BeforeEach
  void seed() {
    TemplateCatalogFixture.seedEligible(jdbc);
    owner = customer("delete-owner-" + UUID.randomUUID());
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

  /** A fresh unclaimed TG draft. */
  private UUID createUnowned() {
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", terms("25000.00"), AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().id();
  }

  /** A fresh TG draft claimed by {@code as}. */
  private UUID createOwned(HttpHeaders as) {
    UUID id = createUnowned();
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(as),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return id;
  }

  private void uploadPdf(UUID id) {
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.singlePage()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.addAll(owner);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/draft",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
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

  private ResponseEntity<String> delete(UUID id, HttpHeaders as) {
    return rest.exchange(
        "/api/agreements/" + id, HttpMethod.DELETE, new HttpEntity<>(as), String.class);
  }

  private ResponseEntity<String> list(HttpHeaders as) {
    return rest.exchange("/api/agreements", HttpMethod.GET, new HttpEntity<>(as), String.class);
  }

  private int count(String table, String column, UUID id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
  }

  private boolean agreementExists(UUID id) {
    return count("agreement", "id", id) == 1;
  }

  private boolean objectExists(String key) {
    try {
      blobStore.get(key);
      return true;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  private UUID ownerOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT owner_identity_id FROM agreement WHERE id = ?", UUID.class, id);
  }

  private String referenceOf(UUID id) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, id);
  }

  private static String draftKey(UUID id) {
    return "drafts/" + id + ".pdf";
  }

  private void assertProblem(ResponseEntity<String> resp, HttpStatus status, String type)
      throws Exception {
    assertThat(resp.getStatusCode()).isEqualTo(status);
    assertThat(json.readTree(resp.getBody()).get("type").asText()).isEqualTo(type);
  }

  // ---- 4.4 happy path and authorisation ----

  @Test
  void theOwnerDeletesAnUnpaidDraftIncludingAPdfLeftBehindByAnEdit() throws Exception {
    UUID id = createOwned(owner);
    uploadPdf(id);
    editTerms(id); // clears the draft pin, keeps the object
    assertThat(
            jdbc.queryForObject(
                "SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, id))
        .isNull();
    assertThat(objectExists(draftKey(id))).isTrue();

    ResponseEntity<String> resp = delete(id, owner);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(resp.getBody()).isNull();
    assertThat(agreementExists(id)).isFalse();
    assertThat(count("signer", "agreement_id", id)).isZero();
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id, HttpMethod.GET, new HttpEntity<>(owner), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(list(owner).getBody()).doesNotContain(id.toString());
    assertThat(objectExists(draftKey(id))).isFalse();
  }

  @Test
  void aDraftThatNeverHadAPdfIsDeleted() {
    UUID id = createOwned(owner);

    assertThat(delete(id, owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(agreementExists(id)).isFalse();
  }

  @Test
  void anotherIdentitysAgreementIsTheSameNotFoundAsAnUnknownOne() throws Exception {
    UUID id = createOwned(owner);
    HttpHeaders stranger = customer("delete-stranger-" + UUID.randomUUID());

    ResponseEntity<String> refused = delete(id, stranger);
    ResponseEntity<String> unknown = delete(UUID.randomUUID(), stranger);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode a = json.readTree(refused.getBody());
    JsonNode b = json.readTree(unknown.getBody());
    for (String field : List.of("type", "title", "detail")) {
      assertThat(a.get(field)).as(field).isNotNull().isEqualTo(b.get(field));
    }
    assertThat(agreementExists(id)).isTrue();
  }

  @Test
  void anUnownedDraftCannotBeDeletedThroughTheEndpoint() {
    UUID id = createUnowned();

    assertThat(delete(id, owner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(agreementExists(id)).isTrue();
  }

  @Test
  void aCallerWithoutASessionIsRefused() {
    UUID id = createOwned(owner);

    assertThat(delete(id, new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(agreementExists(id)).isTrue();
  }

  @Test
  void aDeleteWithoutTheCsrfTokenIsRefused() throws Exception {
    UUID id = createOwned(owner);

    ResponseEntity<String> resp =
        RawClient.on(port)
            .exchange(
                "/api/agreements/" + id, HttpMethod.DELETE, new HttpEntity<>(owner), String.class);

    assertProblem(resp, HttpStatus.FORBIDDEN, "urn:agreementmitra:problem:csrf");
    assertThat(agreementExists(id)).isTrue();
  }

  @Test
  void aBodyNamingAnotherAgreementOrOwnerIsIgnored() {
    UUID mine = createOwned(owner);
    UUID other = createOwned(owner);
    HttpHeaders headers = new HttpHeaders();
    headers.addAll(owner);
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> body = Map.of("id", other, "ownerIdentityId", UUID.randomUUID());

    ResponseEntity<String> resp =
        rest.exchange(
            "/api/agreements/" + mine,
            HttpMethod.DELETE,
            new HttpEntity<>(body, headers),
            String.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(agreementExists(mine)).isFalse();
    assertThat(agreementExists(other)).isTrue();
  }

  // ---- 4.5 refusals, and the list flag from the same rule ----

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
        java.sql.Timestamp.from(Instant.now()));
  }

  private void setPaymentState(UUID id, String state) {
    jdbc.update("UPDATE agreement SET payment_state = ? WHERE id = ?", state, id);
  }

  @Test
  void anythingButAnUnpaidDraftIsRefusedAndUnchangedAndListedNotDeletable() throws Exception {
    UUID finalised = createOwned(owner);
    uploadPdf(finalised);
    seedSigningRequest(finalised);
    UUID withOrder = createOwned(owner);
    seedOrder(withOrder);
    UUID paid = createOwned(owner);
    setPaymentState(paid, "PAID");
    UUID waived = createOwned(owner);
    setPaymentState(waived, "WAIVED");
    UUID draft = createOwned(owner);

    for (UUID id : List.of(finalised, withOrder, paid, waived)) {
      assertProblem(delete(id, owner), HttpStatus.CONFLICT, NOT_DELETABLE);
      assertThat(agreementExists(id)).as(id.toString()).isTrue();
      assertThat(count("signer", "agreement_id", id)).isEqualTo(2);
      assertThat(count("agreement_deletion", "agreement_id", id)).isZero();
    }
    assertThat(objectExists(draftKey(finalised))).isTrue();

    JsonNode rows = json.readTree(list(owner).getBody());
    Map<String, Boolean> deletable = new java.util.HashMap<>();
    rows.forEach(r -> deletable.put(r.get("id").asText(), r.get("deletable").asBoolean()));
    assertThat(deletable)
        .containsEntry(finalised.toString(), false)
        .containsEntry(withOrder.toString(), false)
        .containsEntry(paid.toString(), false)
        .containsEntry(waived.toString(), false)
        .containsEntry(draft.toString(), true);
  }

  // ---- 4.6 the row lock ----

  @Test
  void aDeleteWaitsForAConcurrentFinaliseAndThenRefuses() throws Exception {
    UUID id = createOwned(owner);

    try (Connection tx = dataSource.getConnection()) {
      tx.setAutoCommit(false);
      try (PreparedStatement insert =
          tx.prepareStatement(
              "INSERT INTO signing_request (id, agreement_id, status, version, created_at)"
                  + " VALUES (?, ?, 'PDF_GENERATED', 0, now())")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, id);
        insert.executeUpdate();
      }

      CompletableFuture<ResponseEntity<String>> pending =
          CompletableFuture.supplyAsync(() -> delete(id, owner));

      awaitALockWait();
      assertThat(pending).isNotDone();

      tx.commit();

      assertProblem(pending.get(30, TimeUnit.SECONDS), HttpStatus.CONFLICT, NOT_DELETABLE);
    }
    assertThat(agreementExists(id)).isTrue();
  }

  /** Poll until some backend is blocked on a lock - the DELETE waiting behind our open insert. */
  private void awaitALockWait() throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    while (Instant.now().isBefore(deadline)) {
      Integer waiting =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM pg_stat_activity"
                  + " WHERE datname = current_database() AND wait_event_type = 'Lock'",
              Integer.class);
      if (waiting != null && waiting > 0) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("the DELETE never blocked on the agreement row lock");
  }

  // ---- 4.7 the intake audit row and the deletion record ----

  @Test
  void anIntakeAuditRowSurvivesTheDeleteAndADeletionRecordIsWritten() {
    UUID id = createOwned(owner);
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
        " " + reference.toLowerCase() + " ");

    Instant before = Instant.now();
    assertThat(delete(id, owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    Map<String, Object> audit =
        jdbc.queryForMap(
            "SELECT agreement_id, submitted_reference, outcome FROM stamp_intake_audit"
                + " WHERE id = ?",
            auditId);
    assertThat(audit.get("agreement_id")).isNull();
    assertThat(audit.get("submitted_reference")).isEqualTo(" " + reference.toLowerCase() + " ");
    assertThat(audit.get("outcome")).isEqualTo("REJECTED_ERROR");

    Map<String, Object> record =
        jdbc.queryForMap("SELECT * FROM agreement_deletion WHERE agreement_id = ?", id);
    assertThat(record)
        .containsOnlyKeys(
            "agreement_id", "tracking_reference", "owner_identity_id", "deleted_at", "reason")
        .containsEntry("reason", "OWNER_DELETE")
        .containsEntry("tracking_reference", reference)
        .containsEntry("owner_identity_id", ownerId);
    assertThat(((java.sql.Timestamp) record.get("deleted_at")).toInstant())
        .isAfterOrEqualTo(before.minusSeconds(5));
    // The orphaned audit row joins to the record by its trimmed, upper-cased reference.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM stamp_intake_audit s JOIN agreement_deletion d"
                    + " ON UPPER(BTRIM(s.submitted_reference)) = d.tracking_reference"
                    + " WHERE s.id = ?",
                Integer.class,
                auditId))
        .isEqualTo(1);
  }

  @Test
  void aRefusedDeleteLeavesNoDeletionRecord() {
    UUID notMine = createOwned(customer("delete-other-" + UUID.randomUUID()));
    UUID paid = createOwned(owner);
    setPaymentState(paid, "PAID");

    assertThat(delete(notMine, owner).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(delete(paid, owner).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    assertThat(count("agreement_deletion", "agreement_id", notMine)).isZero();
    assertThat(count("agreement_deletion", "agreement_id", paid)).isZero();
  }

  // ---- 4.8 a failed object removal ----

  @Test
  void aFailedObjectRemovalDoesNotFailTheDelete() {
    UUID id = createOwned(owner);
    uploadPdf(id);
    doThrow(new IllegalStateException("storage down")).when(blobStore).delete(any());

    assertThat(delete(id, owner).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(agreementExists(id)).isFalse();
  }
}
