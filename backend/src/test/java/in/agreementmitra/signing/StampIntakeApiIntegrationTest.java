package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
 * Full-pipeline integration test for staff e-stamp intake against real Postgres + MinIO
 * (Testcontainers): the STAFF-only authorization, the single-use certificate ledger enforced by the
 * database (including under concurrency), the blobs written to object storage, and the FSM.
 *
 * <p>Fixtures are synthetic images with fabricated certificate numbers. No SHCIL endpoint,
 * credential, or real certificate exists anywhere in this repository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class StampIntakeApiIntegrationTest {

  private static final String INTAKE_PATH = "/api/staff/estamp";

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlobStore blobStore;
  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private String staffToken;
  private String customerToken;

  @BeforeEach
  void mintSessions() {
    staffToken =
        StaffSessions.staffSession(
            identityService, handoffService, sessionService, jdbc, "staff-" + UUID.randomUUID());
    customerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "customer-" + UUID.randomUUID());
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * A persisted agreement with an uploaded draft that the customer has FINALISED - the precondition
   * for a stamp upload. Finalising is what places the order and creates the PDF_GENERATED request;
   * intake only ever advances it.
   */
  private UUID agreementWithDraft() {
    UUID id = draftedAgreement();
    finalise(id);
    return id;
  }

  /** A persisted agreement with a draft but NOT finalised - no order exists yet. */
  private UUID draftedAgreement() {
    UUID id = bareAgreement();
    uploadDraft(id, TestPdfs.singlePage());
    return id;
  }

  /**
   * Place the order the way the customer does, and return the tracking reference they are given.
   *
   * <p>The payment gate ships {@code REQUIRED}, so the order is also taken past it here - this file
   * is about certificates and the single-use ledger, not about payment. The gate is still genuinely
   * evaluated on every intake below; it simply passes. The refusal path is covered where it belongs
   * ({@code PaymentGateIntegrationTest}, {@code RazorpayPaymentGateIntegrationTest}).
   */
  private String finalise(UUID agreementId) {
    ResponseEntity<Map> resp =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, Map.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    Payments.waive(jdbc, agreementId);
    return (String) resp.getBody().get("trackingReference");
  }

  private UUID bareAgreement() {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "12 MG Road, Bengaluru",
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
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  private void uploadDraft(UUID agreementId, byte[] pdf) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", namedResource(pdf, "draft.pdf"));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> resp =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  private String staffReferenceOf(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT tracking_reference FROM agreement WHERE id = ?", String.class, agreementId);
  }

  private static ByteArrayResource namedResource(byte[] bytes, String filename) {
    return new ByteArrayResource(bytes) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  private MultiValueMap<String, Object> intakeForm(String reference, String certificateNumber) {
    return intakeForm(reference, certificateNumber, TestImages.certificateScan());
  }

  private MultiValueMap<String, Object> intakeForm(
      String reference, String certificateNumber, byte[] scan) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("scan", namedResource(scan, "certificate.png"));
    form.add("agreementReference", reference);
    form.add("certificateNumber", certificateNumber);
    form.add("issueDate", "2026-01-15");
    form.add("dutyAmount", "500.00");
    form.add("jurisdiction", "KA");
    form.add("descriptionOfDocument", "Rental agreement");
    form.add("purchasedBy", "AgreementMitra Operations");
    return form;
  }

  private ResponseEntity<String> postIntake(String token, MultiValueMap<String, Object> form) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(
        INTAKE_PATH, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
  }

  private String statusOfAgreement(UUID agreementId) {
    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM signing_request WHERE agreement_id = ?", String.class, agreementId);
    return statuses.isEmpty() ? null : statuses.get(0);
  }

  private static String uniqueCertificate() {
    return "IN-KA" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
  }

  // --- happy path ------------------------------------------------------------

  @Test
  void staffUploadStoresBothBlobsPersistsTheCertificateAndDrivesStamped() throws Exception {
    UUID agreementId = agreementWithDraft();
    String certificate = uniqueCertificate();

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(staffReferenceOf(agreementId), certificate));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    // The echo confirms the right instrument, with the certificate number redacted.
    assertThat(resp.getBody()).contains("Bengaluru").contains("***");
    assertThat(resp.getBody()).doesNotContain(certificate);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT stamp_certificate_number, stamped_pdf_key, stamp_scan_key, stamp_duty_amount,"
                + " stamp_jurisdiction, stamp_duty_paid, stamp_certificate_issue_date"
                + " FROM agreement WHERE id = ?",
            agreementId);
    assertThat(row.get("stamp_certificate_number")).isEqualTo(certificate);
    assertThat(row.get("stamp_jurisdiction")).isEqualTo("KA");
    assertThat(row.get("stamp_duty_paid")).isEqualTo(true); // a REAL certificate was purchased
    assertThat(row.get("stamped_pdf_key")).isEqualTo("stamped/" + agreementId + ".pdf");
    assertThat(row.get("stamp_scan_key")).isEqualTo("estamp-scans/" + agreementId);

    // The scan is retained as the evidence artifact...
    assertThat(blobStore.get("estamp-scans/" + agreementId)).isNotEmpty();
    // ...and the composite has the scan as page 1, with NOTHING stamped onto the agreement page.
    // The number printed there was whatever staff transcribed at intake, so the document could
    // assert a stamp it could not vouch for; it is persisted (asserted above) but never printed.
    // See PdfStampComposer#compose and PdfStampComposerTest.
    byte[] stamped = blobStore.get("stamped/" + agreementId + ".pdf");
    try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(stamped)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc))
          .doesNotContain(certificate)
          .doesNotContain("e-Stamp Certificate No.");
    }

    assertThat(statusOfAgreement(agreementId)).isEqualTo("STAMPED");

    // The attempt is audited.
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM stamp_intake_audit WHERE agreement_id = ? AND outcome = ?",
                Long.class,
                agreementId,
                "ACCEPTED"))
        .isEqualTo(1);
  }

  // --- authorization ---------------------------------------------------------

  @Test
  void anonymousUploadIs401AndNothingIsPersisted() {
    UUID agreementId = agreementWithDraft();
    long auditsBefore = auditCount();

    ResponseEntity<String> resp =
        postIntake(null, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED"); // untouched
    assertThat(auditCount()).isEqualTo(auditsBefore);
  }

  @Test
  void customerUploadIs403AndNothingIsPersisted() {
    UUID agreementId = agreementWithDraft();
    long auditsBefore = auditCount();

    ResponseEntity<String> resp =
        postIntake(customerToken, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED"); // untouched
    assertThat(auditCount()).isEqualTo(auditsBefore);
  }

  @Test
  void refusalIsIdenticalWhetherOrNotTheAgreementExists() {
    UUID agreementId = agreementWithDraft();

    ResponseEntity<String> existing =
        postIntake(customerToken, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));
    ResponseEntity<String> missing =
        postIntake(customerToken, intakeForm(unknownReference(), uniqueCertificate()));

    // Same status and same body (bar the wall-clock timestamp Boot stamps on every error): the
    // filter chain decides authorization before any lookup, so the endpoint cannot be used as an
    // existence oracle for another customer's agreement.
    assertThat(existing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(missing.getStatusCode()).isEqualTo(existing.getStatusCode());
    assertThat(withoutTimestamp(missing.getBody())).isEqualTo(withoutTimestamp(existing.getBody()));
  }

  /** Strip Boot's wall-clock {@code timestamp} field, which differs between any two responses. */
  private static String withoutTimestamp(String body) {
    return body == null ? null : body.replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\",?", "");
  }

  // --- validation ------------------------------------------------------------

  @Test
  void missingMandatoryMetadataIs400WithAFieldErrorListAndNoSideEffect() {
    UUID agreementId = agreementWithDraft();
    MultiValueMap<String, Object> form =
        intakeForm(staffReferenceOf(agreementId), uniqueCertificate());
    form.remove("certificateNumber");
    form.remove("dutyAmount");

    ResponseEntity<String> resp = postIntake(staffToken, form);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).contains("errors").contains("certificateNumber");
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");
  }

  @Test
  void aPdfInsteadOfAScanIs400AndLeavesTheRequestUntouched() {
    UUID agreementId = agreementWithDraft();
    MultiValueMap<String, Object> form =
        intakeForm(staffReferenceOf(agreementId), uniqueCertificate(), TestPdfs.singlePage());

    ResponseEntity<String> resp = postIntake(staffToken, form);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(resp.getBody()).contains("invalid-upload");
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED"); // staff can retry
  }

  @Test
  void aDecompressionBombIs400WithoutExhaustingMemory() {
    UUID agreementId = agreementWithDraft();
    MultiValueMap<String, Object> form =
        intakeForm(
            staffReferenceOf(agreementId), uniqueCertificate(), TestImages.bombPng(30_000, 30_000));

    ResponseEntity<String> resp = postIntake(staffToken, form);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");
  }

  @Test
  void anUnknownReferenceIs404() {
    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(unknownReference(), uniqueCertificate()));
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theDisplayOnlyTrackingNumberIsNotAcceptedAsALookupKey() {
    UUID agreementId = agreementWithDraft();
    String trackingNumber =
        jdbc.queryForObject(
            "SELECT 'AM-' || UPPER(RIGHT(REPLACE(id::text, '-', ''), 6)) || '-'"
                + " || TO_CHAR(start_date, 'DDMMYY') FROM agreement WHERE id = ?",
            String.class,
            agreementId);

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(trackingNumber, uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");
  }

  @Test
  void aMistypedReferenceIsRejectedByTheCheckCharacter() {
    UUID agreementId = agreementWithDraft();
    String reference = staffReferenceOf(agreementId);
    char[] mistyped = reference.toCharArray();
    mistyped[4] = mistyped[4] == '9' ? '8' : '9';

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(new String(mistyped), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");
  }

  @Test
  void anUnparseableDraftDrivesStampFailed() {
    UUID agreementId = bareAgreement();
    // Passes the draft upload's magic-byte check but is not a real PDF - fails at composition.
    uploadDraft(agreementId, "%PDF-1.4 not a real pdf".getBytes(StandardCharsets.UTF_8));
    finalise(agreementId);

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(resp.getBody()).contains("stamp-failed").doesNotContain("not a real pdf");
    assertThat(statusOfAgreement(agreementId)).isEqualTo("STAMP_FAILED");
  }

  // --- single-use ledger -----------------------------------------------------

  @Test
  void reusingACertificateOnAnotherAgreementIs409AndTheFirstIsUnchanged() {
    UUID first = agreementWithDraft();
    UUID second = agreementWithDraft();
    String certificate = uniqueCertificate();
    assertThat(
            postIntake(staffToken, intakeForm(staffReferenceOf(first), certificate))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(staffReferenceOf(second), certificate));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).contains("certificate-already-used");
    // The refusal names no other agreement.
    assertThat(resp.getBody()).doesNotContain(first.toString());
    // The first agreement keeps its stamp; the second gets none.
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number FROM agreement WHERE id = ?", String.class, first))
        .isEqualTo(certificate);
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number FROM agreement WHERE id = ?",
                String.class,
                second))
        .isNull();
  }

  @Test
  void normalisationMakesCasingAndSpacingVariantsCollideAsDuplicates() {
    UUID first = agreementWithDraft();
    UUID second = agreementWithDraft();
    String certificate = uniqueCertificate();
    assertThat(
            postIntake(staffToken, intakeForm(staffReferenceOf(first), certificate))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> resp =
        postIntake(
            staffToken,
            intakeForm(staffReferenceOf(second), "  " + certificate.toLowerCase() + "  "));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void reUploadingToAnAlreadyStampedAgreementIs409AndLeavesTheExistingStamp() {
    UUID agreementId = agreementWithDraft();
    String first = uniqueCertificate();
    assertThat(
            postIntake(staffToken, intakeForm(staffReferenceOf(agreementId), first))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            jdbc.queryForObject(
                "SELECT stamp_certificate_number FROM agreement WHERE id = ?",
                String.class,
                agreementId))
        .isEqualTo(first);
  }

  @Test
  void concurrentUploadsOfOneCertificateLetExactlyOneThrough() throws Exception {
    int attempts = 4;
    String certificate = uniqueCertificate();
    List<String> references =
        IntStream.range(0, attempts).mapToObj(i -> staffReferenceOf(agreementWithDraft())).toList();

    ExecutorService pool = Executors.newFixedThreadPool(attempts);
    CountDownLatch gate = new CountDownLatch(1);
    List<Future<Integer>> futures =
        references.stream()
            .map(
                reference ->
                    pool.submit(
                        () -> {
                          gate.await();
                          return postIntake(staffToken, intakeForm(reference, certificate))
                              .getStatusCode()
                              .value();
                        }))
            .toList();
    gate.countDown();

    long accepted = 0;
    for (Future<Integer> future : futures) {
      if (future.get() == HttpStatus.OK.value()) {
        accepted++;
      }
    }
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    // The DATABASE constraint decides, not an application pre-check: one purchased stamp, one use.
    assertThat(accepted).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM agreement WHERE UPPER(BTRIM(stamp_certificate_number)) = ?",
                Long.class,
                certificate))
        .isEqualTo(1);
  }

  // --- the order is placed at finalisation, never by intake --------------------

  @Test
  void anUnfinalisedAgreementCannotBeStampedAndNoOrderIsCreated() {
    UUID agreementId = draftedAgreement(); // drafted but NOT finalised
    long requestsBefore = signingRequestCount();

    ResponseEntity<String> resp =
        postIntake(staffToken, intakeForm(staffReferenceOf(agreementId), uniqueCertificate()));

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(resp.getBody()).contains("order-not-placed");
    // Intake created nothing: the customer alone decides when their agreement freezes.
    assertThat(signingRequestCount()).isEqualTo(requestsBefore);
    assertThat(statusOfAgreement(agreementId)).isNull();
  }

  @Test
  void finalisingPlacesTheOrderReturnsTheTrackingReferenceAndFreezesTheDraft() {
    UUID agreementId = draftedAgreement();

    String reference = finalise(agreementId);

    // The customer is handed the SAME persisted reference staff will quote at intake.
    assertThat(reference).isEqualTo(staffReferenceOf(agreementId));
    assertThat(statusOfAgreement(agreementId)).isEqualTo("PDF_GENERATED");
    // Frozen from this instant: the document staff stamp and the parties sign is the one the
    // customer finalised, so replacing the draft afterwards is refused.
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("file", namedResource(TestPdfs.singlePage(), "draft.pdf"));
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    ResponseEntity<String> replace =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/draft",
            new HttpEntity<>(form, headers),
            String.class);
    assertThat(replace.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(replace.getBody()).contains("draft-frozen");
  }

  @Test
  void finalisingTwiceIsIdempotentAndPlacesOneOrder() {
    UUID agreementId = draftedAgreement();

    String first = finalise(agreementId);
    long afterFirst = signingRequestCount();
    String second = finalise(agreementId);

    assertThat(second).isEqualTo(first);
    assertThat(signingRequestCount()).isEqualTo(afterFirst);
  }

  // --- staff console queue ----------------------------------------------------

  @Test
  void theQueueListsOutstandingOrdersAndDropsThemOnceStamped() {
    UUID agreementId = agreementWithDraft();
    String reference = staffReferenceOf(agreementId);

    assertThat(queueReferences(staffToken)).contains(reference);

    assertThat(postIntake(staffToken, intakeForm(reference, uniqueCertificate())).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    // Stamped work leaves the queue - completed orders never accumulate on the console.
    assertThat(queueReferences(staffToken)).doesNotContain(reference);
  }

  @Test
  void theQueueExcludesAgreementsThatWereNeverFinalised() {
    UUID drafted = draftedAgreement();
    assertThat(queueReferences(staffToken)).doesNotContain(staffReferenceOf(drafted));
  }

  @Test
  void theQueueCarriesEnoughContextToActWithoutRetypingTheReference() {
    UUID agreementId = agreementWithDraft();
    String reference = staffReferenceOf(agreementId);

    ResponseEntity<String> resp = getQueue(staffToken);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody())
        .contains(reference)
        .contains("Bengaluru")
        .contains("waitingSeconds")
        .contains("awaitingSince");
    // STAFF-only, and it deliberately DOES carry party names: buying the certificate means naming
    // both parties on the vendor's form, so the row lists every party with their name and father's
    // name (staff-queue-fulfilment-context reversed the row's original non-PII shape). The PII rule
    // this must still respect is that party names never reach a LOG line -- not that they are
    // absent from a role-gated response. See StampQueueEntry.
    assertThat(resp.getBody()).contains("Asha Owner").contains("Tara Tenant");
    // Still excluded, deliberately: contact details, rent/deposit, and the full street address --
    // only the property city. A fulfilment queue carries the least data that lets someone do the
    // job.
    assertThat(resp.getBody()).doesNotContain("asha@example.com").doesNotContain("12 MG Road");
  }

  @Test
  void theQueueIsStaffOnlyAndLeaksNoQueueSizeToOthers() {
    agreementWithDraft();

    assertThat(getQueue(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    ResponseEntity<String> asCustomer = getQueue(customerToken);
    assertThat(asCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    // The refusal body is Boot's generic error - it carries no entry, count, or reference.
    assertThat(asCustomer.getBody()).doesNotContain("trackingReference").doesNotContain("AM");
  }

  private ResponseEntity<String> getQueue(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(
        INTAKE_PATH + "/queue", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private List<String> queueReferences(String token) {
    ResponseEntity<List> resp =
        rest.exchange(
            INTAKE_PATH + "/queue", HttpMethod.GET, new HttpEntity<>(bearer(token)), List.class);
    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<String> references = new ArrayList<>();
    for (Object row : resp.getBody()) {
      references.add((String) ((Map<?, ?>) row).get("trackingReference"));
    }
    return references;
  }

  private static HttpHeaders bearer(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private long signingRequestCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM signing_request", Long.class);
  }

  /** A well-formed reference (valid check character) that belongs to no agreement. */
  private static String unknownReference() {
    return "AM222222222";
  }

  // --- schema ----------------------------------------------------------------

  @Test
  void flywayV14ManualEstampMigrationIsApplied() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '14' AND success = true",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void everyAgreementCarriesAUniqueStaffReference() {
    UUID first = bareAgreement();
    UUID second = bareAgreement();
    assertThat(staffReferenceOf(first)).isNotBlank().isNotEqualTo(staffReferenceOf(second));
  }

  @Test
  void theStaffReferenceIsImmutableAcrossAnEdit() {
    UUID agreementId = bareAgreement();
    String before = staffReferenceOf(agreementId);

    // An owner edit replaces the terms wholesale; the reference must survive untouched.
    jdbc.update("UPDATE agreement SET property_address = ? WHERE id = ?", "9 New Rd", agreementId);

    assertThat(staffReferenceOf(agreementId)).isEqualTo(before);
  }

  private long auditCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM stamp_intake_audit", Long.class);
  }
}
