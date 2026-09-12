package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The payment gate in <b>OPTIONAL</b> mode.
 *
 * <p>{@code OPTIONAL} is no longer the default - the gate now ships {@code REQUIRED} - but it is
 * still a supported mode, and it is the documented way to relax the gate if enforcement ever has to
 * come off in a hurry. That makes it exactly the kind of path that rots: nothing else in the suite
 * exercises it any more, and the moment it is needed is the moment nobody wants to discover it
 * broke six months ago. So it gets its own class with an explicit override.
 *
 * <p>What it must prove is one thing: with the gate {@code OPTIONAL}, an {@code UNPAID} agreement
 * still reaches the end of the fulfilment pipeline. Payment state is recorded either way - the gate
 * only decides whether it blocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"payment.mode=OPTIONAL", "esign.provider=leegality"})
class PaymentGateOptionalIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  /**
   * Pin every agreement these tests create to an ELIGIBLE jurisdiction. Since
   * jurisdiction-checkout-gating, an agreement with no pinned template has no duty jurisdiction and
   * is refused at finalise, checkout, e-stamp intake and eSign initiation - so a fixture that
   * creates a bare agreement can no longer reach the steps these tests exercise. The seeder is
   * local/sandbox-only, so the row is inserted here.
   */
  @BeforeEach
  void seedEligibleTemplate() {
    in.agreementmitra.support.TemplateCatalogFixture.seedEligible(jdbc);
  }

  @Autowired private IdentityService identityService;
  @Autowired private HandoffService handoffService;
  @Autowired private SessionService sessionService;

  private String staffToken;

  @BeforeEach
  void mintSession() {
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "opt-staff-" + UUID.randomUUID());
  }

  private UUID createFinalisedAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
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

  private ResponseEntity<String> uploadStamp(UUID agreementId) {
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
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.setBearerAuth(staffToken);
    return rest.exchange(
        "/api/staff/estamp", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
  }

  @Test
  void anUnpaidAgreementIsStillStampedWhenTheGateIsOptional() {
    UUID agreementId = createFinalisedAgreement();
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("UNPAID");

    // Nobody has paid, and the gate lets it through anyway - that IS the permissive mode.
    assertThat(uploadStamp(agreementId).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM signing_request WHERE agreement_id = ?",
                String.class,
                agreementId))
        .isEqualTo("STAMPED");
    // The state was still RECORDED. Permissive is not the same as blind: an operator can still see
    // that this order went out unpaid.
    assertThat(
            jdbc.queryForObject(
                "SELECT payment_state FROM agreement WHERE id = ?", String.class, agreementId))
        .isEqualTo("UNPAID");
  }

  @Test
  void theRelaxedModeIsObservableAtRuntime() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(staffToken);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/staff/payments/gate", HttpMethod.GET, new HttpEntity<>(headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("OPTIONAL");
  }
}
