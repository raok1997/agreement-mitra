package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementSummaryResponse;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestPdfs;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration for agreement ownership (agreement-ownership CR): claim/read-scoping,
 * list-mine, edit, and the SecurityConfig matcher order -- against real Postgres (Testcontainers)
 * and the real security chain, over a random port via {@link TestRestTemplate}. Booting under
 * {@code ddl-auto: validate} against V1..V12 also proves the schema/mapping match (task 5.4).
 * {@code disabledWithoutDocker = true} skips (not fails) without a Docker daemon.
 *
 * <p>An authenticated caller is seeded through the real session layer: find-or-create an identity,
 * issue a handoff, exchange it for an opaque session value sent as the session cookie. No PII
 * beyond a dummy email/name is used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementOwnershipIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  /** Mint a live opaque session for a fresh identity and return its session cookie value. */
  private String sessionFor(String subject) {
    UUID identityId =
        identityService.findOrCreate(
            "google", subject, subject + "@example.com", true, "T " + subject);
    String handoff = handoffService.issue(identityId);
    return sessionService.exchange(handoff).value();
  }

  private static HttpHeaders bearer(String session) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(session));
    return headers;
  }

  private static Map<String, Object> signer(String name, String email, String role) {
    String[] parts = name.split(" ", 2);
    return Map.of(
        "firstName",
        parts[0],
        "lastName",
        parts.length > 1 ? parts[1] : "X",
        "fatherName",
        "Father " + parts[0],
        "currentAddress",
        "Addr " + parts[0],
        "email",
        email,
        "role",
        role);
  }

  private static Map<String, Object> validBody() {
    return Map.of(
        "propertyAddress",
        "12 MG Road, Bengaluru",
        "monthlyRent",
        new BigDecimal("25000.00"),
        "securityDeposit",
        new BigDecimal("50000.00"),
        "startDate",
        "2026-01-01",
        "endDate",
        "2026-12-01",
        "signers",
        List.of(
            signer("Asha Owner", "asha@example.com", "OWNER"),
            signer("Tara Tenant", "tara@example.com", "TENANT")));
  }

  /** Anonymously create an agreement (owner null) and return its id. */
  private UUID createAnonymous() {
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", validBody(), AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().id();
  }

  // ---- 5.4 boot-and-validate ----

  @Test
  void bootsAndValidatesAgainstV12() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '12' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
    // The owner column exists and is nullable (an anonymous draft is legitimately owner-less).
    Integer col =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'agreement' AND column_name = 'owner_identity_id'",
            Integer.class);
    assertThat(col).isEqualTo(1);
  }

  // ---- 5.5 claim + read-scoping (no oracle) ----

  @Test
  void claimMakesReadsOwnerScopedWithNoOracle() {
    String sessionA = sessionFor("owner-A");
    String sessionB = sessionFor("owner-B");
    UUID id = createAnonymous();

    // Unowned: capability-readable by anyone presenting the id (anonymous 200).
    assertThat(rest.getForEntity("/api/agreements/" + id, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    // A claims it.
    ResponseEntity<AgreementResponse> claimed =
        rest.exchange(
            "/api/agreements/" + id + "/claim",
            HttpMethod.POST,
            new HttpEntity<>(bearer(sessionA)),
            AgreementResponse.class);
    assertThat(claimed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(claimed.getBody().id()).isEqualTo(id);

    // Now owner-only: A reads 200, anonymous + B both get 404 (indistinguishable from unknown).
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(bearer(sessionA)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(rest.getForEntity("/api/agreements/" + id, String.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id,
                    HttpMethod.GET,
                    new HttpEntity<>(bearer(sessionB)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    // Double-claim by another identity -> 404 (no oracle), and A still owns it.
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(bearer(sessionB)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    UUID owner =
        jdbc.queryForObject("SELECT owner_identity_id FROM agreement WHERE id = ?", UUID.class, id);
    assertThat(owner).isNotNull();

    // Re-claim by the SAME owner is idempotent (200).
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(bearer(sessionA)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  // ---- 5.6 list mine: only the caller's rows, most-recent first, correct derived status ----

  @Test
  void listReturnsOnlyMineMostRecentlyEditedFirstWithDerivedStatus() {
    String sessionA = sessionFor("list-A");
    String sessionB = sessionFor("list-B");

    UUID first = createAnonymous();
    claim(first, sessionA);
    UUID second = createAnonymous();
    claim(second, sessionA);
    UUID bees = createAnonymous();
    claim(bees, sessionB);

    // Simulate signing having started on `first` -> its derived status becomes IN_PROGRESS/frozen.
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at) "
            + "VALUES (?, ?, 'PDF_GENERATED', 0, ?)",
        UUID.randomUUID(),
        first,
        Timestamp.from(Instant.now()));

    ResponseEntity<AgreementSummaryResponse[]> mine =
        rest.exchange(
            "/api/agreements",
            HttpMethod.GET,
            new HttpEntity<>(bearer(sessionA)),
            AgreementSummaryResponse[].class);
    assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<AgreementSummaryResponse> rows = List.of(mine.getBody());
    // Only A's two rows, most recently edited first (neither edited since creation, and claiming
    // is not an edit, so the later-created one leads).
    assertThat(rows).extracting(AgreementSummaryResponse::id).containsExactly(second, first);
    assertThat(bySecond(rows, second).editable()).isTrue(); // no signing request -> DRAFT/editable
    assertThat(bySecond(rows, first).editable()).isFalse(); // has a request -> IN_PROGRESS/frozen
  }

  private static AgreementSummaryResponse bySecond(List<AgreementSummaryResponse> rows, UUID id) {
    return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  private void claim(UUID id, String session) {
    rest.exchange(
        "/api/agreements/" + id + "/claim",
        HttpMethod.POST,
        new HttpEntity<>(bearer(session)),
        AgreementResponse.class);
  }

  // ---- my-agreements-rich-rows: list order, names, and what moves last_edited_at ----

  private static Map<String, Object> bodyWith(
      String address, List<Map<String, Object>> signers, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("propertyAddress", address);
    body.put("monthlyRent", new BigDecimal("25000.00"));
    body.put("securityDeposit", new BigDecimal("50000.00"));
    body.put("startDate", "2026-01-01");
    body.put("endDate", "2026-12-01");
    body.put("signers", signers);
    body.putAll(extra);
    return body;
  }

  private UUID createClaimed(Map<String, Object> body, String session) {
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", body, AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = created.getBody().id();
    claim(id, session);
    return id;
  }

  private ResponseEntity<String> put(UUID id, Map<String, Object> body, String session) {
    return rest.exchange(
        "/api/agreements/" + id,
        HttpMethod.PUT,
        new HttpEntity<>(body, bearer(session)),
        String.class);
  }

  /** Read back from the database: Postgres microseconds on both sides of every comparison. */
  private Timestamp lastEditedAt(UUID id) {
    return jdbc.queryForObject(
        "SELECT last_edited_at FROM agreement WHERE id = ?", Timestamp.class, id);
  }

  private List<String> namesByEntryPosition(UUID id) {
    return jdbc.queryForList(
        "SELECT name FROM signer WHERE agreement_id = ? ORDER BY entry_position", String.class, id);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> listRaw(String session) {
    ResponseEntity<Map[]> listed =
        rest.exchange(
            "/api/agreements", HttpMethod.GET, new HttpEntity<>(bearer(session)), Map[].class);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    return List.of((Map<String, Object>[]) listed.getBody());
  }

  private List<AgreementSummaryResponse> list(String session) {
    ResponseEntity<AgreementSummaryResponse[]> listed =
        rest.exchange(
            "/api/agreements",
            HttpMethod.GET,
            new HttpEntity<>(bearer(session)),
            AgreementSummaryResponse[].class);
    assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
    return List.of(listed.getBody());
  }

  @Test
  void editingAnOlderAgreementMovesItToTheTop() {
    String session = sessionFor("rich-order");
    UUID older = createClaimed(validBody(), session);
    UUID newer = createClaimed(validBody(), session);

    assertThat(
            put(
                    older,
                    bodyWith(
                        "7 Edited Rd, Pune",
                        List.of(
                            signer("Asha Owner", "asha@example.com", "OWNER"),
                            signer("Tara Tenant", "tara@example.com", "TENANT")),
                        Map.of()),
                    session)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    List<AgreementSummaryResponse> rows = list(session);
    assertThat(rows).extracting(AgreementSummaryResponse::id).containsExactly(older, newer);
    assertThat(rows.get(0).lastEditedAt()).isAfter(rows.get(0).createdAt());
  }

  @Test
  void listCarriesPartyNamesByRoleInEntryOrderAndNothingElseOfAParty() {
    String session = sessionFor("rich-names");
    createClaimed(
        bodyWith(
            "12 MG Road, Bengaluru",
            List.of(
                signer("Ramesh Kumar Reddy", "ramesh@example.com", "OWNER"),
                signer("Priya Sharma", "priya@example.com", "TENANT"),
                signer("Lakshmi Devi Reddy", "lakshmi@example.com", "OWNER")),
            Map.of()),
        session);

    ResponseEntity<String> raw =
        rest.exchange(
            "/api/agreements", HttpMethod.GET, new HttpEntity<>(bearer(session)), String.class);
    assertThat(raw.getHeaders().getCacheControl()).contains("no-store");

    Map<String, Object> row = listRaw(session).get(0);
    assertThat(row.keySet())
        .containsExactlyInAnyOrder(
            "id",
            "trackingNumber",
            "propertyAddress",
            "monthlyRent",
            "startDate",
            "endDate",
            "durationMonths",
            "createdAt",
            "lastEditedAt",
            "ownerNames",
            "tenantNames",
            "status",
            "editable");
    assertThat(row.get("ownerNames"))
        .isEqualTo(List.of("Ramesh Kumar Reddy", "Lakshmi Devi Reddy"));
    assertThat(row.get("tenantNames")).isEqualTo(List.of("Priya Sharma"));
  }

  @Test
  void draftingSurfaceEditsMoveLastEditedAt() {
    String session = sessionFor("rich-moves");
    UUID id = createClaimed(validBody(), session);

    // Parties only: same terms, one name changed.
    Timestamp before = lastEditedAt(id);
    assertThat(
            put(
                    id,
                    bodyWith(
                        "12 MG Road, Bengaluru",
                        List.of(
                            signer("Asha Renamed", "asha@example.com", "OWNER"),
                            signer("Tara Tenant", "tara@example.com", "TENANT")),
                        Map.of()),
                    session)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    Timestamp afterParties = lastEditedAt(id);
    assertThat(afterParties).isAfter(before);

    // Contacts, with a changed email, before any payment.
    ResponseEntity<String> contacts =
        rest.exchange(
            "/api/agreements/" + id + "/contacts",
            HttpMethod.PATCH,
            new HttpEntity<>(
                Map.of("contacts", List.of(contactFor(id, session, "changed@example.com"))),
                bearer(session)),
            String.class);
    assertThat(contacts.getStatusCode()).isEqualTo(HttpStatus.OK);
    Timestamp afterContacts = lastEditedAt(id);
    assertThat(afterContacts).isAfter(afterParties);

    // A draft upload.
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.singlePage()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders multipart = bearer(session);
    multipart.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + id + "/draft",
                    new HttpEntity<>(form, multipart),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(lastEditedAt(id)).isAfter(afterContacts);
  }

  @Test
  void claimPaymentAndWaiverLeaveLastEditedAtUnchanged() {
    String customer = sessionFor("rich-still");
    String staff =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "rich-staff-" + UUID.randomUUID());

    UUID paid = createAnonymous();
    Timestamp beforeClaim = lastEditedAt(paid);
    claim(paid, customer);
    assertThat(lastEditedAt(paid)).isEqualTo(beforeClaim);

    HttpHeaders confirm = bearer(staff);
    confirm.setContentType(MediaType.APPLICATION_JSON);
    assertThat(
            rest.postForEntity(
                    "/api/staff/payments/" + paid + "/confirm",
                    new HttpEntity<>(
                        "{\"amount\":\"1499.00\",\"currency\":\"INR\",\"reference\":\"RICH-"
                            + UUID.randomUUID()
                            + "\"}",
                        confirm),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(lastEditedAt(paid)).isEqualTo(beforeClaim);

    UUID waived = createAnonymous();
    Timestamp beforeWaive = lastEditedAt(waived);
    assertThat(
            rest.exchange(
                    "/api/staff/payments/" + waived + "/waive",
                    HttpMethod.POST,
                    new HttpEntity<>(null, bearer(staff)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(lastEditedAt(waived)).isEqualTo(beforeWaive);
  }

  @Test
  void aRequestCannotSetLastEditedAtOrAPartyPosition() {
    String session = sessionFor("rich-inject");
    String future = "2099-01-01T00:00:00Z";
    Map<String, Object> owner =
        new LinkedHashMap<>(signer("Zed Owner", "zed@example.com", "OWNER"));
    owner.put("position", 5);
    Map<String, Object> tenant =
        new LinkedHashMap<>(signer("Amy Tenant", "amy@example.com", "TENANT"));
    tenant.put("position", 0);
    Map<String, Object> injected =
        Map.of(
            "lastEditedAt", future,
            "last_edited_at", future,
            "captureData", Map.of("lastEditedAt", future, "last_edited_at", future, "note", "x"));

    UUID id = createClaimed(bodyWith("1 A St", List.of(owner, tenant), injected), session);
    Boolean equalsCreated =
        jdbc.queryForObject(
            "SELECT last_edited_at = created_at FROM agreement WHERE id = ?", Boolean.class, id);
    assertThat(equalsCreated).isTrue();
    assertThat(namesByEntryPosition(id)).containsExactly("Zed Owner", "Amy Tenant");

    Timestamp before = lastEditedAt(id);
    assertThat(
            put(id, bodyWith("1 A St", List.of(tenant, owner), injected), session)
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
    Timestamp after = lastEditedAt(id);
    assertThat(after).isAfter(before);
    assertThat(after.toInstant()).isBefore(Instant.parse(future));
    assertThat(namesByEntryPosition(id)).containsExactly("Amy Tenant", "Zed Owner");

    AgreementResponse read =
        rest.exchange(
                "/api/agreements/" + id,
                HttpMethod.GET,
                new HttpEntity<>(bearer(session)),
                AgreementResponse.class)
            .getBody();
    assertThat(read.captureData()).doesNotContainKeys("lastEditedAt", "last_edited_at");
  }

  @Test
  void partiesReadBackInEntryOrderAfterARowIsRewritten() {
    String session = sessionFor("rich-position");
    UUID id =
        createClaimed(
            bodyWith(
                "1 A St",
                List.of(
                    signer("Anil Owner", "anil@example.com", "OWNER"),
                    signer("Bina Owner", "bina@example.com", "OWNER"),
                    signer("Tejas Tenant", "tejas@example.com", "TENANT")),
                Map.of()),
            session);

    // Rewrite A's row: an UPDATE writes a new row version at the end of the heap.
    UUID anil =
        jdbc.queryForObject(
            "SELECT id FROM signer WHERE agreement_id = ? AND name = 'Anil Owner'", UUID.class, id);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/contacts",
                    HttpMethod.PATCH,
                    new HttpEntity<>(
                        Map.of(
                            "contacts",
                            List.of(
                                Map.of("signerId", anil.toString(), "email", "anil2@example.com"))),
                        bearer(session)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    // Precondition: heap order now differs from entry order, so the assertions below can fail.
    assertThat(
            jdbc.queryForList(
                "SELECT name FROM signer WHERE agreement_id = ? ORDER BY ctid", String.class, id))
        .containsExactly("Bina Owner", "Tejas Tenant", "Anil Owner");

    assertThat(readSignerNames(id, session))
        .containsExactly("Anil Owner", "Bina Owner", "Tejas Tenant");
    assertThat(summaryOf(id, session).ownerNames()).containsExactly("Anil Owner", "Bina Owner");

    assertThat(
            put(
                    id,
                    bodyWith(
                        "1 A St",
                        List.of(
                            signer("Bina Owner", "bina@example.com", "OWNER"),
                            signer("Anil Owner", "anil@example.com", "OWNER"),
                            signer("Tejas Tenant", "tejas@example.com", "TENANT")),
                        Map.of()),
                    session)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(readSignerNames(id, session))
        .containsExactly("Bina Owner", "Anil Owner", "Tejas Tenant");
    assertThat(summaryOf(id, session).ownerNames()).containsExactly("Bina Owner", "Anil Owner");
  }

  private List<String> readSignerNames(UUID id, String session) {
    return rest
        .exchange(
            "/api/agreements/" + id,
            HttpMethod.GET,
            new HttpEntity<>(bearer(session)),
            AgreementResponse.class)
        .getBody()
        .signers()
        .stream()
        .map(AgreementResponse.SignerResponse::name)
        .toList();
  }

  private AgreementSummaryResponse summaryOf(UUID id, String session) {
    return list(session).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
  }

  // ---- contacts: unowned OR owned-by-me; somebody else's -> 404 ----

  /**
   * Signing in must not cost a customer the ability to save their own contacts.
   *
   * <p>The contacts route once served unowned agreements only, so a signed-in customer reaching the
   * pre-payment contacts screen got a 404 on save - and, because the draft is sent immediately
   * after that save, never received their draft either. The screen is the same screen whether or
   * not they signed in.
   */
  @Test
  void ownerCanSaveContactsOnTheirOwnAgreement() {
    String session = sessionFor("contacts-owner");
    UUID id = createAnonymous();
    claim(id, session);

    ResponseEntity<AgreementResponse> saved =
        rest.exchange(
            "/api/agreements/" + id + "/contacts",
            HttpMethod.PATCH,
            new HttpEntity<>(
                Map.of("contacts", List.of(contactFor(id, session, "owner.saved@example.com"))),
                bearer(session)),
            AgreementResponse.class);

    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(saved.getBody().signers())
        .extracting(AgreementResponse.SignerResponse::email)
        .contains("owner.saved@example.com");
  }

  @Test
  void contactsOnSomebodyElsesAgreementAre404ForOwnerAndAnonymousAlike() {
    String owner = sessionFor("contacts-A");
    String stranger = sessionFor("contacts-B");
    UUID id = createAnonymous();
    claim(id, owner);

    HttpEntity<Map<String, Object>> body =
        new HttpEntity<>(
            Map.of("contacts", List.of(contactFor(id, owner, "stranger@example.com"))),
            bearer(stranger));
    ResponseEntity<String> asStranger =
        rest.exchange("/api/agreements/" + id + "/contacts", HttpMethod.PATCH, body, String.class);
    ResponseEntity<String> anonymously =
        rest.exchange(
            "/api/agreements/" + id + "/contacts",
            HttpMethod.PATCH,
            new HttpEntity<>(
                Map.of("contacts", List.of(contactFor(id, owner, "anon@example.com")))),
            String.class);

    // Identical answers: ownership must not be probeable through this route.
    assertThat(asStranger.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(anonymously.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /**
   * A contacts entry addressed to whichever party the agreement actually has.
   *
   * <p>Read with the OWNER's session: once an agreement is claimed, an anonymous read of it is
   * refused, so an anonymous fixture lookup would hand back an empty body and the test would fail
   * for a reason that has nothing to do with contacts.
   */
  private Map<String, Object> contactFor(UUID agreementId, String session, String email) {
    ResponseEntity<AgreementResponse> agreement =
        rest.exchange(
            "/api/agreements/" + agreementId,
            HttpMethod.GET,
            new HttpEntity<>(bearer(session)),
            AgreementResponse.class);
    UUID signerId = agreement.getBody().signers().get(0).id();
    return Map.of("signerId", signerId.toString(), "email", email);
  }

  // ---- 5.7 edit: owner + pre-signing-request; frozen -> 409; non-owner -> 404; owner from session
  // ----

  @Test
  void editReplacesTermsWhenOwnerAndUnfrozenThenFreezes() {
    String sessionA = sessionFor("edit-A");
    String sessionB = sessionFor("edit-B");
    UUID id = createAnonymous();
    claim(id, sessionA);

    // A body carrying an ownerIdentityId/id is ignored (anti-mass-assignment): owner is the
    // session.
    Map<String, Object> editBody =
        Map.of(
            "id",
            UUID.randomUUID().toString(),
            "ownerIdentityId",
            UUID.randomUUID().toString(),
            "propertyAddress",
            "99 New Street, Pune",
            "monthlyRent",
            new BigDecimal("30000.00"),
            "securityDeposit",
            new BigDecimal("60000.00"),
            "startDate",
            "2027-01-01",
            "endDate",
            "2028-01-01",
            "signers",
            List.of(
                signer("Meera Owner", "meera@example.com", "OWNER"),
                signer("Nikhil Tenant", "nikhil@example.com", "TENANT")));

    ResponseEntity<AgreementResponse> edited =
        rest.exchange(
            "/api/agreements/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(editBody, bearer(sessionA)),
            AgreementResponse.class);
    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(edited.getBody().id()).isEqualTo(id); // the path id wins, the body id is ignored
    assertThat(edited.getBody().propertyAddress()).isEqualTo("99 New Street, Pune");
    assertThat(edited.getBody().signers()).hasSize(2); // parties replaced wholesale
    assertThat(edited.getBody().signers())
        .extracting(AgreementResponse.SignerResponse::email)
        .containsExactlyInAnyOrder("meera@example.com", "nikhil@example.com");

    // A non-owner PUT -> 404 (no oracle).
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id,
                    HttpMethod.PUT,
                    new HttpEntity<>(editBody, bearer(sessionB)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    // Once a signing request exists, edit is frozen -> 409.
    jdbc.update(
        "INSERT INTO signing_request (id, agreement_id, status, version, created_at) "
            + "VALUES (?, ?, 'PDF_GENERATED', 0, ?)",
        UUID.randomUUID(),
        id,
        Timestamp.from(Instant.now()));
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id,
                    HttpMethod.PUT,
                    new HttpEntity<>(editBody, bearer(sessionA)),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  // ---- 5.8 SecurityConfig matcher order ----

  @Test
  void listRequiresAuthWhileCreateAndCapabilityReadStayOpen() {
    // Unauthenticated list is denied (401/403) -- the authenticated matcher is ordered first.
    assertThat(rest.getForEntity("/api/agreements", String.class).getStatusCode().value())
        .isIn(401, 403);

    // Anonymous create still permitted (an empty body reaches MVC and 400s, not blocked as 403).
    HttpHeaders json = new HttpHeaders();
    json.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    assertThat(
            rest.exchange(
                    "/api/agreements", HttpMethod.POST, new HttpEntity<>("{}", json), String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);

    // Anonymous capability read of an unowned draft still permitted (200).
    UUID id = createAnonymous();
    assertThat(rest.getForEntity("/api/agreements/" + id, String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
