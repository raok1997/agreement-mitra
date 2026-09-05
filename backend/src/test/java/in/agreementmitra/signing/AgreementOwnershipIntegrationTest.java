package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.signing.api.AgreementSummaryResponse;
import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration for agreement ownership (agreement-ownership CR): claim/read-scoping,
 * list-mine, edit, and the SecurityConfig matcher order -- against real Postgres (Testcontainers)
 * and the real security chain, over a random port via {@link TestRestTemplate}. Booting under
 * {@code ddl-auto: validate} against V1..V12 also proves the schema/mapping match (task 5.4).
 * {@code disabledWithoutDocker = true} skips (not fails) without a Docker daemon.
 *
 * <p>An authenticated caller is seeded through the real session layer: find-or-create an identity,
 * issue a handoff, exchange it for an opaque session value used as the {@code Bearer}. No PII
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

  /** Mint a live opaque session for a fresh identity and return its Bearer value. */
  private String sessionFor(String subject) {
    UUID identityId =
        identityService.findOrCreate(
            "google", subject, subject + "@example.com", true, "T " + subject);
    String handoff = handoffService.issue(identityId);
    return sessionService.exchange(handoff).value();
  }

  private static HttpHeaders bearer(String session) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(session);
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
  void listReturnsOnlyMineMostRecentFirstWithDerivedStatus() {
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
    // Only A's two rows, most-recent first (second created after first).
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
