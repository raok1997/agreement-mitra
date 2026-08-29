package in.agreementmitra.signing.signingrequest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The staff stamp queue as a <b>purchasing</b> surface, end to end against real Postgres + MinIO.
 *
 * <p>An operator buys the e-stamp off this response: the vendor's form asks for the first party,
 * the second party, and the state. So the assertions here are about whether the row is
 * <em>sufficient</em> - if a name or the state is missing, the operator is back to querying the
 * database by hand, which is what the console exists to prevent.
 *
 * <p>The counterweight is that these rows now carry personal data. The role gate is therefore
 * exercised in the same file as the data it protects, so nobody can widen one without seeing the
 * other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StampQueueFulfilmentIntegrationTest {

  private static final String QUEUE = "/api/staff/estamp/queue";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  /** The seeder is local/sandbox-only, so the catalog is seeded here explicitly. */
  private static final String LAYER_SET_REF = "documents/template/examples/layers/";

  private static final String TEMPLATE_NAME = "Residential Rental (Telangana)";

  private UUID templateId;
  private String staffToken;
  private String customerToken;

  /**
   * Seed the one template these tests pin to. Deliberately additive - NOT a {@code DELETE FROM
   * template}: the schema is shared across the suite, and deleting rows other agreements are pinned
   * to leaves those agreements on this very queue with a dangling template id, which is a fixture
   * bug that reads exactly like a product bug.
   *
   * <p>A high version wins {@code publishedTemplateIdFor}'s order-by, so this row is the one an
   * agreement created here pins to regardless of what else the suite has seeded for (TG,
   * residential).
   */
  @BeforeEach
  void seedCatalog() {
    templateId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO template (id, name, description, type, state, language, version, status,"
            + " layer_set_ref, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        templateId,
        TEMPLATE_NAME,
        TEMPLATE_NAME + " blurb",
        "residential",
        "TG",
        "en",
        999,
        "PUBLISHED",
        LAYER_SET_REF,
        java.sql.Timestamp.from(java.time.Instant.now()));
  }

  @BeforeEach
  void signIn() {
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "queue-staff-" + UUID.randomUUID());
    customerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "queue-cust-" + UUID.randomUUID());
  }

  @Test
  void theQueueCarriesTheTemplateStateAndEveryPartyWithTheirFatherName() {
    UUID agreementId = createFinalisedAgreement("TG", "residential");

    String body = queueAs(staffToken).getBody();

    Map<String, Object> row = rowFor(body, agreementId);

    // The state decides which state's stamp paper to buy.
    assertThat(row.get("templateState")).isEqualTo("TG");
    assertThat(row.get("templateName")).isEqualTo(TEMPLATE_NAME);
    // Both parties with both father's names, owners first -- so "first party" and "second party"
    // mean the same thing on every refresh, and the certificate can be filled from this row alone.
    assertThat(partiesOf(row))
        .containsExactly("OWNER|Asha Owner|Ravi Owner", "TENANT|Tara Tenant|Hari Tenant");
  }

  @Test
  void theQueueStillExcludesContactsRentAndTheFullAddress() {
    createFinalisedAgreement("TG", "residential");

    String body = queueAs(staffToken).getBody();

    // Widening the row to what purchasing needs is not licence to widen it to everything.
    assertThat(body).doesNotContain("asha@example.com");
    assertThat(body).doesNotContain("tara@example.com");
    assertThat(body).doesNotContain("25000.00");
    assertThat(body).doesNotContain("50000.00");
    assertThat(body).doesNotContain("12 MG Road, Bengaluru");
    // City only.
    assertThat(body).contains("Bengaluru");
  }

  @Test
  void anAgreementPinnedToAnUnpublishedTemplateStaysOnTheQueue() {
    UUID agreementId = createFinalisedAgreement("TG", "residential");
    // The catalog moved on after this agreement pinned its template.
    // By the agreement's OWN pin, not by the id seeded above: the suite may hold other published
    // rows for (TG, residential), and deprecating the wrong one would leave this test asserting
    // nothing.
    jdbc.update(
        "UPDATE template SET status = 'DEPRECATED' WHERE id ="
            + " (SELECT template_id FROM agreement WHERE id = ?)",
        agreementId);

    String body = queueAs(staffToken).getBody();

    // Outstanding work does not vanish because a template was deprecated.
    Map<String, Object> row = rowFor(body, agreementId);
    assertThat(row.get("templateName")).isNull();
    assertThat(row.get("templateState")).isNull();
    // The parties are still there, so the operator can still identify the instrument.
    assertThat(partiesOf(row)).contains("OWNER|Asha Owner|Ravi Owner");
  }

  @Test
  void aNonStaffCallerLearnsNeitherANameNorTheQueueSize() {
    createFinalisedAgreement("TG", "residential");

    ResponseEntity<String> asCustomer = queueAs(customerToken);
    ResponseEntity<String> anonymous =
        rest.exchange(QUEUE, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

    assertThat(asCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    for (ResponseEntity<String> refused : List.of(asCustomer, anonymous)) {
      String body = refused.getBody() == null ? "" : refused.getBody();
      assertThat(body).doesNotContain("Asha Owner");
      assertThat(body).doesNotContain("Ravi Owner");
      assertThat(body).doesNotContain("Tara Tenant");
      assertThat(body).doesNotContain("trackingReference");
    }
  }

  // --- fixtures --------------------------------------------------------------

  /**
   * The one queue row for {@code agreementId}, parsed. The suite shares a database, so other
   * classes' orders sit on this queue too - asserting against the whole payload would happily pass
   * on someone else's row.
   */
  private static Map<String, Object> rowFor(String body, UUID agreementId) {
    List<Map<String, Object>> rows;
    try {
      rows = new ObjectMapper().readValue(body, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new AssertionError("queue response was not a JSON array: " + body, e);
    }
    return rows.stream()
        .filter(row -> agreementId.toString().equals(row.get("agreementId")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no queue row for " + agreementId));
  }

  /** The row's parties flattened to {@code ROLE|name|father}, in the order the server sent them. */
  private static List<String> partiesOf(Map<String, Object> row) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parties = (List<Map<String, Object>>) row.get("parties");
    return parties.stream()
        .map(p -> p.get("role") + "|" + p.get("name") + "|" + p.get("fatherName"))
        .toList();
  }

  private ResponseEntity<String> queueAs(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    ResponseEntity<String> response =
        rest.exchange(QUEUE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    return response;
  }

  /** A finalised agreement pinned to the published template for {@code (state, type)}. */
  private UUID createFinalisedAgreement(String state, String type) {
    Map<String, Object> body =
        Map.of(
            "propertyAddress",
            "12 MG Road, Bengaluru",
            "monthlyRent",
            "25000.00",
            "securityDeposit",
            "50000.00",
            "startDate",
            "2026-01-01",
            "endDate",
            "2026-12-01",
            "state",
            state,
            "type",
            type,
            "signers",
            List.of(
                Map.of(
                    "firstName", "Asha",
                    "lastName", "Owner",
                    "fatherName", "Ravi Owner",
                    "currentAddress", "1 A St",
                    "email", "asha@example.com",
                    "role", "OWNER"),
                Map.of(
                    "firstName", "Tara",
                    "lastName", "Tenant",
                    "fatherName", "Hari Tenant",
                    "currentAddress", "3 C St",
                    "email", "tara@example.com",
                    "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID id = UUID.fromString((String) created.getBody().get("id"));

    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "file",
        new ByteArrayResource(TestPdfs.withEsignAnchors()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    assertThat(
            rest.postForEntity(
                    "/api/agreements/" + id + "/draft",
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(
            rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    return id;
  }

  // --- optional signing kick-off (task 11.6) ---------------------------------

  private ResponseEntity<String> uploadStamp(UUID agreementId, boolean initiateSigning) {
    // The gate ships REQUIRED, and intake refuses an unpaid order before it looks at the scan.
    jdbc.update("UPDATE agreement SET payment_state = 'PAID' WHERE id = ?", agreementId);
    String reference =
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
    var form = new LinkedMultiValueMap<String, Object>();
    form.add(
        "scan",
        new ByteArrayResource(TestImages.certificateScan()) {
          @Override
          public String getFilename() {
            return "certificate.png";
          }
        });
    form.add("agreementReference", reference);
    form.add(
        "certificateNumber",
        "IN-KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase());
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", "500.00");
    form.add("jurisdiction", "KA");
    if (initiateSigning) {
      form.add("initiateSigning", "true");
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(staffToken);
    return rest.exchange(
        "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
  }

  @Test
  void anUploadWithoutTheInstructionAttachesTheStampAndStartsNothing() throws Exception {
    UUID agreementId = createFinalisedAgreement("TG", "residential");

    ResponseEntity<String> response = uploadStamp(agreementId, false);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("signingInitiated").asBoolean()).isFalse();
    assertThat(body.get("signingNotStartedReason").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("STAMPED");
  }

  @Test
  void anUploadThatAsksForSigningKeepsTheStampEvenWhenSigningCannotStart() throws Exception {
    // The test profile points the provider at no reachable vendor, which is exactly the failure
    // this has to survive: the certificate is spent by then, so the stamp must stand and the
    // response must say plainly that signing did not start.
    UUID agreementId = createFinalisedAgreement("TG", "residential");

    ResponseEntity<String> response = uploadStamp(agreementId, true);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("certificateNumberRedacted").asText()).startsWith("***");
    assertThat(body.get("signingInitiated").asBoolean()).isFalse();
    assertThat(body.get("signingNotStartedReason").asText()).isNotBlank();
    // The stamp is attached and the request is still STAMPED, so signing can be started again.
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number IS NOT NULL FROM agreement WHERE id = ?",
                Boolean.class,
                agreementId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("STAMPED");
  }
}
