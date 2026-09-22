package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.GotenbergTestConfig;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import org.springframework.util.MultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The executed Telangana deed states the stamp duty of the certificate staff attached (change
 * {@code stamp-duty-amount-from-certificate}). Reproduces agreement {@code AMPSFTXU5KV}, whose
 * stamped instrument printed {@code [ Stamp duty paid (INR) ]}: a generated TG draft, finalised and
 * paid, stamped through the real intake endpoint, then read back out of object storage.
 *
 * <p>Runs the production {@code sets/rental} layers ({@code test,sandbox}: the catalog seeder and
 * the registry-backed layer source) against real Postgres + MinIO + Gotenberg, because the defect
 * only exists in the rendered PDF. Skips without Docker. Dummy data only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, GotenbergTestConfig.class})
@ActiveProfiles({"test", "sandbox"})
@Testcontainers(disabledWithoutDocker = true)
class StampDutyFromCertificateIntegrationTest {

  private static final String PLACEHOLDER = "[ Stamp duty paid (INR) ]";

  /** What the pre-stamp draft shows in the stamp duty row: a visible provision, never a figure. */
  private static final String PROVISION = "Stamp duty paid (INR) [ Provision for stamp duty ]";

  /** Covers the TG duty the rules recompute for the fixture (no frozen quote: a waived order). */
  private static final String CERTIFICATE_DUTY = "5000.00";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;
  private String staffToken;

  @BeforeEach
  void mintStaffSession() {
    staffToken =
        StaffSessions.staffSession(
            identityService, handoffService, sessionService, jdbc, "staff-" + UUID.randomUUID());
  }

  @Test
  void theStampedDeedStatesTheCertificateDutyAndKeepsTheDraftExecutionDate() throws IOException {
    UUID agreementId = telanganaAgreement();
    // What an old client could still have saved into the capture state: it must never print.
    jdbc.update(
        "UPDATE agreement SET capture_state = jsonb_set(COALESCE(capture_state,"
            + " '{\"data\":{},\"activeSections\":[]}'::jsonb), '{data,stampDutyAmount}',"
            + " '\"98765\"') WHERE id = ?",
        agreementId);
    generateDraft(agreementId);

    // The generate path recorded the date the draft printed (no agreementDate was captured).
    assertThat(draftExecutionDate(agreementId)).isEqualTo(LocalDate.now());
    String draft = text(blobStore.get("drafts/" + agreementId + ".pdf"));
    // The draft the parties review before paying keeps a visible provision for the duty.
    assertThat(draft)
        .contains("STATUTORY (TELANGANA)") // headings print upper-cased
        .contains(PROVISION)
        .doesNotContain(PLACEHOLDER)
        .doesNotContain("The stamp duty paid on this Agreement is INR")
        .doesNotContain("98765");

    // Pretend the draft was rendered on an earlier day, so a re-render that re-read today's date
    // instead of the recorded one would show up in the stamped deed.
    jdbc.update(
        "UPDATE agreement SET draft_execution_date = DATE '2026-01-05' WHERE id = ?", agreementId);

    placePaidOrder(agreementId);
    ResponseEntity<String> resp = stamp(agreementId);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(signingStatus(agreementId)).isEqualTo("STAMPED");
    String stamped = text(blobStore.get("stamped/" + agreementId + ".pdf"));
    assertThat(stamped)
        .contains("Stamp duty paid (INR) " + CERTIFICATE_DUTY)
        .contains("The stamp duty paid on this Agreement is INR " + CERTIFICATE_DUTY + ".")
        .contains("05-Jan-2026")
        .doesNotContain("Provision for stamp duty")
        .doesNotContain(PLACEHOLDER)
        .doesNotContain("98765");
    // The stored pre-stamp draft is untouched.
    assertThat(text(blobStore.get("drafts/" + agreementId + ".pdf"))).isEqualTo(draft);
  }

  @Test
  void anUploadedDraftIsStampedAsBeforeWithoutARender() throws IOException {
    UUID agreementId = telanganaAgreement();
    generateDraft(agreementId);
    uploadDraft(agreementId, TestPdfs.singlePage()); // replaces the rendered draft, keeps the pin
    assertThat(draftExecutionDate(agreementId)).isNull();

    placePaidOrder(agreementId);
    ResponseEntity<String> resp = stamp(agreementId);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(signingStatus(agreementId)).isEqualTo("STAMPED");
    byte[] stamped = blobStore.get("stamped/" + agreementId + ".pdf");
    try (PDDocument doc = Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2); // certificate + the one uploaded page
    }
    assertThat(text(stamped)).doesNotContain("Stamp duty paid (INR)");
  }

  // --- helpers ---------------------------------------------------------------

  private UUID telanganaAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
            "propertyAddress", "F1, Sri Sai Krishna Apartments, Kukatpally, Hyderabad",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-01-01",
            "endDate", "2026-12-01",
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
    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private void generateDraft(UUID agreementId) {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/agreements/" + agreementId + "/document", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void uploadDraft(UUID agreementId, byte[] pdf) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", named(pdf, "draft.pdf"));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private void placePaidOrder(UUID agreementId) {
    ResponseEntity<String> resp =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Payments.waive(jdbc, agreementId);
  }

  private ResponseEntity<String> stamp(UUID agreementId) {
    return stamp(agreementId, CERTIFICATE_DUTY);
  }

  private ResponseEntity<String> stamp(UUID agreementId, String dutyAmount) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("scan", named(TestImages.certificateScan(), "certificate.png"));
    form.add(
        "agreementReference",
        jdbc.queryForObject(
            "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId));
    form.add(
        "certificateNumber",
        "IN-TG" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase());
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", dutyAmount);
    form.add("jurisdiction", "TG");
    form.add("descriptionOfDocument", "Rental agreement");
    form.add("purchasedBy", "AgreementMitra Operations");
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(staffToken);
    return rest.exchange(
        "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
  }

  private LocalDate draftExecutionDate(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT draft_execution_date FROM agreement WHERE id = ?", LocalDate.class, agreementId);
  }

  private String signingStatus(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT status FROM signing_request WHERE agreement_id = ?", String.class, agreementId);
  }

  private static String uncheckedText(byte[] pdf) {
    try {
      return text(pdf);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** The PDF's text with every whitespace run collapsed, so wrapped lines still match. */
  private static String text(byte[] pdf) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
    }
  }

  private static ByteArrayResource named(byte[] bytes, String filename) {
    return new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
