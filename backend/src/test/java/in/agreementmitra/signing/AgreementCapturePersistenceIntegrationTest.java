package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.TestPdfs;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration for agreement capture-state persistence (agreement-capture-persistence
 * / M5) against real Postgres (Testcontainers) + MinIO over a random port. Booting under {@code
 * ddl-auto: validate} against V1..V13 also proves the jsonb {@code capture_state} mapping matches
 * the schema (task 6.4).
 *
 * <p>The {@code documents} projection is a {@link MockitoBean}: generate-as-draft is driven without
 * a Gotenberg/Chromium render, and the {@link DocumentProjectionRequest} the render builds is
 * captured so the parity assertion (the stored draft renders the added optional section + the
 * dynamic value) is verified on the projection INPUT rather than by scraping binary PDF bytes
 * (design D3 / task 6.6). {@code disabledWithoutDocker = true} skips (not fails) without a Docker
 * daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementCapturePersistenceIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  @MockitoBean private DocumentProjectionApi documentProjection;

  private static DocumentProjectionResult fakeResult() {
    return new DocumentProjectionResult(
        TestPdfs.singlePage(),
        new EffectiveTemplateIdentity("rental-base", "sha256:test", Map.of("base", 1)));
  }

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

  /** A valid create body (both roles present) with no capture state. */
  private static Map<String, Object> baseBody() {
    Map<String, Object> body = new HashMap<>();
    body.put("propertyAddress", "12 MG Road, Bengaluru");
    body.put("monthlyRent", new BigDecimal("25000.00"));
    body.put("securityDeposit", new BigDecimal("50000.00"));
    body.put("startDate", "2026-01-01");
    body.put("endDate", "2026-12-01");
    body.put(
        "signers",
        List.of(
            signer("Asha Owner", "asha@example.com", "OWNER"),
            signer("Tara Tenant", "tara@example.com", "TENANT")));
    return body;
  }

  private UUID create(Map<String, Object> body) {
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", body, AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().id();
  }

  // ---- 6.4 boot-and-validate: app starts against V1..V13 under ddl-auto: validate ----

  @Test
  void bootsAndValidatesAgainstV13() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '13' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
    Integer col =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_name = 'agreement' AND column_name = 'capture_state' "
                + "AND data_type = 'jsonb'",
            Integer.class);
    assertThat(col).isEqualTo(1);
  }

  // ---- 6.5 round-trip: create stores it, read returns it; edit replaces wholesale + clears pin
  // ----

  @Test
  void createStoresCaptureStateAndReadReturnsIt() {
    Map<String, Object> body = baseBody();
    body.put("captureData", Map.of("lockInMonths", "6", "petAllowed", "true"));
    body.put("activeSections", List.of("Pets", "Lock-in"));
    UUID id = create(body);

    // Unowned capability read returns the stored capture state.
    ResponseEntity<AgreementResponse> read =
        rest.getForEntity("/api/agreements/" + id, AgreementResponse.class);
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(read.getBody().captureData())
        .containsEntry("lockInMonths", "6")
        .containsEntry("petAllowed", "true");
    assertThat(read.getBody().activeSections()).containsExactlyInAnyOrder("Pets", "Lock-in");
  }

  @Test
  void editReplacesCaptureStateWholesaleAndClearsThePinnedDraft() {
    String session = sessionFor("capture-editor");
    Map<String, Object> body = baseBody();
    body.put("captureData", Map.of("lockInMonths", "6"));
    body.put("activeSections", List.of("Lock-in"));
    UUID id = create(body);

    // Claim, then generate-as-draft so a draft key + template pin exist to be cleared by the edit.
    rest.exchange(
        "/api/agreements/" + id + "/claim",
        HttpMethod.POST,
        new HttpEntity<>(bearer(session)),
        AgreementResponse.class);
    when(documentProjection.generate(any())).thenReturn(fakeResult());
    assertThat(
            rest.postForEntity("/api/agreements/{id}/document", null, String.class, id)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            jdbc.queryForObject(
                "SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, id))
        .isNotNull();

    // Edit with a NEW capture state (owner from session).
    Map<String, Object> edit = baseBody();
    edit.put("captureData", Map.of("petAllowed", "false"));
    edit.put("activeSections", List.of("Pets"));
    ResponseEntity<AgreementResponse> edited =
        rest.exchange(
            "/api/agreements/" + id,
            HttpMethod.PUT,
            new HttpEntity<>(edit, bearer(session)),
            AgreementResponse.class);
    assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);

    // Wholesale replace: the prior "Lock-in" / lockInMonths are gone.
    assertThat(edited.getBody().captureData()).containsOnlyKeys("petAllowed");
    assertThat(edited.getBody().activeSections()).containsExactly("Pets");
    // The pinned draft was cleared so the next generate rebuilds from the edited capture state.
    assertThat(
            jdbc.queryForObject(
                "SELECT draft_pdf_key FROM agreement WHERE id = ?", String.class, id))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT template_content_hash FROM agreement WHERE id = ?", String.class, id))
        .isNull();
  }

  // ---- 6.6 parity: the stored draft renders the added optional section + the dynamic value ----

  @Test
  void generateAsDraftFeedsTheStoredCaptureStateIntoTheProjection() {
    Map<String, Object> body = baseBody();
    body.put("captureData", Map.of("lockInMonths", "6", "petAllowed", "true"));
    body.put("activeSections", List.of("Pets"));
    UUID id = create(body);

    when(documentProjection.generate(any())).thenReturn(fakeResult());
    assertThat(
            rest.postForEntity("/api/agreements/{id}/document", null, String.class, id)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    // The added optional section is passed as the projection's sections argument (parity with
    // preview).
    assertThat(req.getValue().activeSections()).containsExactly("Pets");
    // The dynamic values flow through the data map; the fixed columns stay authoritative on top.
    assertThat(req.getValue().data())
        .containsEntry("lockInMonths", "6")
        .containsEntry("petAllowed", "true")
        .containsEntry("propertyAddress", "12 MG Road, Bengaluru");
  }

  // ---- 6.7 legacy fallback: null capture state renders via the fixed-column mapping, unchanged
  // ----

  @Test
  void legacyAgreementWithNoCaptureStateRendersViaTheFixedColumnMapping() {
    UUID id = create(baseBody()); // no captureData / activeSections -> null capture state

    // Sanity: the row stored a NULL capture_state (fixed-fields-only client).
    assertThat(
            jdbc.queryForObject(
                "SELECT capture_state FROM agreement WHERE id = ?", String.class, id))
        .isNull();

    when(documentProjection.generate(any())).thenReturn(fakeResult());
    assertThat(
            rest.postForEntity("/api/agreements/{id}/document", null, String.class, id)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ArgumentCaptor<DocumentProjectionRequest> req =
        ArgumentCaptor.forClass(DocumentProjectionRequest.class);
    org.mockito.Mockito.verify(documentProjection).generate(req.capture());
    // Fixed-column mapping, no optional sections -- unchanged from before this change.
    assertThat(req.getValue().activeSections()).isEmpty();
    assertThat(req.getValue().data()).containsEntry("propertyAddress", "12 MG Road, Bengaluru");
    assertThat(req.getValue().data()).doesNotContainKey("lockInMonths");
  }
}
