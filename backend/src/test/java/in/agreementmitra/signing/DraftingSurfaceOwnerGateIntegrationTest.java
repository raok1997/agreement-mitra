package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.documents.api.DocumentProjectionApi;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.EffectiveTemplateIdentity;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.SessionCookie;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TemplateCatalogFixture;
import in.agreementmitra.support.TestPdfs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The drafting surface - generate, draft upload, id-bound preview and finalise - is owner-scoped
 * once an agreement is claimed (draft-attach-owner-gate). Real Postgres + MinIO + the real security
 * chain over a random port. The {@code documents} projection is mocked, with the same bean set as
 * {@code AgreementCapturePersistenceIntegrationTest} so the Spring context is shared.
 *
 * <p>Each case uses a fresh TG agreement: a served finalise needs an eligible jurisdiction, and it
 * freezes generate and upload afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DraftingSurfaceOwnerGateIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private BlobStore blobStore;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  @MockitoBean private DocumentProjectionApi documentProjection;

  private static final HttpHeaders ANONYMOUS = new HttpHeaders();

  @BeforeEach
  void seedEligibleTemplateAndStubRender() {
    TemplateCatalogFixture.seedEligible(jdbc);
    when(documentProjection.generate(any()))
        .thenReturn(
            new DocumentProjectionResult(
                TestPdfs.singlePage(),
                new EffectiveTemplateIdentity("rental-base", "sha256:test", Map.of("base", 1)),
                "2026-09-10"));
  }

  // ---- helpers ----

  private HttpHeaders customer(String subject) {
    return bearer(
        StaffSessions.customerSession(identityService, handoffService, sessionService, subject));
  }

  private HttpHeaders staff(String subject) {
    return bearer(
        StaffSessions.staffSession(identityService, handoffService, sessionService, jdbc, subject));
  }

  private static HttpHeaders bearer(String session) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, SessionCookie.header(session));
    return headers;
  }

  private static Map<String, Object> party(String first, String last, String role) {
    return Map.of(
        "firstName",
        first,
        "lastName",
        last,
        "fatherName",
        "Father " + first,
        "currentAddress",
        "1 A St",
        "email",
        first.toLowerCase() + "@example.com",
        "role",
        role);
  }

  /** A fresh, unclaimed agreement in an eligible jurisdiction (TG). */
  private UUID createTg() {
    Map<String, Object> body =
        Map.of(
            "state", TemplateCatalogFixture.ELIGIBLE_STATE,
            "type", TemplateCatalogFixture.TYPE,
            "propertyAddress", "12 MG Road, Hyderabad",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
            "signers", List.of(party("Asha", "Owner", "OWNER"), party("Tara", "Tenant", "TENANT")));
    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", body, AgreementResponse.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return created.getBody().id();
  }

  private void claim(UUID id, HttpHeaders owner) {
    assertThat(
            rest.exchange(
                    "/api/agreements/" + id + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(owner),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> generate(UUID id, HttpHeaders as) {
    return rest.exchange(
        "/api/agreements/" + id + "/document", HttpMethod.POST, new HttpEntity<>(as), String.class);
  }

  private ResponseEntity<String> upload(UUID id, HttpHeaders as) {
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
    headers.addAll(as);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return rest.exchange(
        "/api/agreements/" + id + "/draft",
        HttpMethod.POST,
        new HttpEntity<>(form, headers),
        String.class);
  }

  private ResponseEntity<String> preview(UUID id, HttpHeaders as) {
    return rest.exchange(
        "/api/agreements/" + id + "/preview", HttpMethod.GET, new HttpEntity<>(as), String.class);
  }

  private ResponseEntity<String> finalise(UUID id, HttpHeaders as) {
    return rest.exchange(
        "/api/agreements/" + id + "/finalise", HttpMethod.POST, new HttpEntity<>(as), String.class);
  }

  /** The four gated routes, finalise last (it freezes generate and upload). */
  private List<BiFunction<UUID, HttpHeaders, ResponseEntity<String>>> routes() {
    return List.of(this::generate, this::upload, this::preview, this::finalise);
  }

  private void assertServed(UUID id, HttpHeaders as) {
    for (var route : routes()) {
      assertThat(route.apply(id, as).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
  }

  /** Each route refuses {@code as} with exactly the problem an unknown id gets. */
  private void assertRefusedAsUnknown(UUID id, HttpHeaders as) throws Exception {
    for (var route : routes()) {
      ResponseEntity<String> refused = route.apply(id, as);
      ResponseEntity<String> unknown = route.apply(UUID.randomUUID(), as);
      assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
      JsonNode a = json.readTree(refused.getBody());
      JsonNode b = json.readTree(unknown.getBody());
      for (String field : List.of("type", "title", "detail")) {
        assertThat(a.get(field)).as(field).isNotNull().isEqualTo(b.get(field));
      }
    }
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap(
        "SELECT owner_identity_id, draft_pdf_key, template_content_hash, last_edited_at"
            + " FROM agreement WHERE id = ?",
        id);
  }

  private int signingRequests(UUID id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM signing_request WHERE agreement_id = ?", Integer.class, id);
  }

  // ---- 3.1 who is served ----

  @Test
  void anUnclaimedAgreementStaysOpenToAnyLinkHolder() {
    assertServed(createTg(), ANONYMOUS);
  }

  @Test
  void theOwnerOfAClaimedAgreementIsServed() {
    UUID id = createTg();
    HttpHeaders owner = customer("gate-owner-served");
    claim(id, owner);

    assertServed(id, owner);
    assertThat(signingRequests(id)).isEqualTo(1);
  }

  // ---- 3.1 + 3.2 non-owners get the unknown-id 404 and change nothing ----

  @Test
  void nonOwnersGetTheUnknownAgreement404AndChangeNothing() throws Exception {
    UUID id = createTg();
    HttpHeaders owner = customer("gate-owner-a");
    claim(id, owner);
    assertThat(generate(id, owner).getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> before = row(id);
    byte[] storedBytes = blobStore.get((String) before.get("draft_pdf_key"));

    // A refused write must not land: a different render result would show up in the bytes.
    when(documentProjection.generate(any()))
        .thenReturn(
            new DocumentProjectionResult(
                TestPdfs.pages(2),
                new EffectiveTemplateIdentity("rental-base", "sha256:other", Map.of("base", 2)),
                "2026-09-11"));

    assertRefusedAsUnknown(id, ANONYMOUS);
    assertRefusedAsUnknown(id, customer("gate-stranger-b"));
    assertRefusedAsUnknown(id, staff("gate-staff"));

    Map<String, Object> after = row(id);
    assertThat(after).isEqualTo(before);
    assertThat(blobStore.get((String) after.get("draft_pdf_key"))).isEqualTo(storedBytes);
    assertThat(signingRequests(id)).isZero();
  }

  // ---- 3.3 no state oracle: a placed order is still a 404, never a 409/400 ----

  @Test
  void aNonOwnerCannotLearnThatTheOrderIsPlaced() throws Exception {
    UUID id = createTg();
    HttpHeaders owner = customer("gate-owner-ordered");
    claim(id, owner);
    assertThat(generate(id, owner).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(finalise(id, owner).getStatusCode()).isEqualTo(HttpStatus.OK);
    // The owner now meets the freeze, which a non-owner must never see.
    assertThat(upload(id, owner).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

    assertRefusedAsUnknown(id, ANONYMOUS);
    assertRefusedAsUnknown(id, customer("gate-stranger-ordered"));
  }
}
