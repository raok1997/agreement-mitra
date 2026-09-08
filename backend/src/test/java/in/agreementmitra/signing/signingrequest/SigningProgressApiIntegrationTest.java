package in.agreementmitra.signing.signingrequest;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import in.agreementmitra.identity.IdentityService;
import in.agreementmitra.identity.oauth.HandoffService;
import in.agreementmitra.identity.session.SessionService;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.StaffSessions;
import in.agreementmitra.support.TestImages;
import in.agreementmitra.support.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-party signing progress over HTTP, against real Postgres + MinIO and a stubbed provider.
 *
 * <p>Two things are being pinned: that a party can see <b>each</b> signer's individual status
 * rather than only the aggregate, and that the view leaks nothing it should not - no eKYC-derived
 * signer data the provider returned, no other party's signing URL, no provider credential, and no
 * other customer's agreement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SigningProgressApiIntegrationTest {

  private static final String CREATE_URL = "/api/v3.0/sign/request";
  private static final String DETAILS_URL = "/api/v3.3/document/details";
  private static final String MAC_KEY = "progress-mac-key";

  private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

  static {
    WIREMOCK.start();
  }

  @DynamicPropertySource
  static void providerProperties(DynamicPropertyRegistry registry) {
    registry.add("esign.leegality.base-url", () -> WIREMOCK.baseUrl() + "/api/");
    registry.add("esign.leegality.auth-token", () -> "it-auth-token");
    registry.add("esign.leegality.webhook-secret", () -> MAC_KEY);
    registry.add("esign.leegality.profile-id", () -> "it-profile");
  }

  @AfterAll
  static void stopWiremock() {
    WIREMOCK.stop();
  }

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
  private String ownerToken;
  private String strangerToken;

  @BeforeEach
  void reset() {
    WIREMOCK.resetAll();
    staffToken =
        StaffSessions.staffSession(
            identityService,
            handoffService,
            sessionService,
            jdbc,
            "progress-staff-" + UUID.randomUUID());
    ownerToken =
        StaffSessions.customerSession(
            identityService, handoffService, sessionService, "progress-owner-" + UUID.randomUUID());
    strangerToken =
        StaffSessions.customerSession(
            identityService,
            handoffService,
            sessionService,
            "progress-stranger-" + UUID.randomUUID());
  }

  // --- fixtures --------------------------------------------------------------

  private UUID createSignRequestedAgreement(String documentId) {
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

    uploadDraft(id);
    assertThat(
            rest.postForEntity("/api/agreements/" + id + "/finalise", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    // The payment gate ships REQUIRED. This file is about per-party progress, not payment, so the
    // order is taken past the gate here; the gate is still evaluated at intake and at signing
    // initiation below, it simply passes.
    Payments.waive(jdbc, id);
    uploadStamp(id);
    WIREMOCK.stubFor(
        post(urlEqualTo(CREATE_URL))
            .willReturn(
                okJson(
                    "{\"status\":\"SUCCESS\",\"data\":{\"documentId\":\""
                        + documentId
                        + "\",\"invitees\":[{\"inviteeId\":\"INV-1\",\"signUrl\":\"https://sign/OWNERSECRET\","
                        + "\"expiryDate\":\"2026-01-01\"},{\"inviteeId\":\"INV-2\","
                        + "\"signUrl\":\"https://sign/TENANTSECRET\",\"expiryDate\":\"2026-01-02\"}]}}")));
    assertThat(
            rest.postForEntity("/api/signing/" + id + "/request", null, String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    return id;
  }

  private void uploadDraft(UUID agreementId) {
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
                    "/api/agreements/" + agreementId + "/draft",
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private void uploadStamp(UUID agreementId) {
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
    assertThat(
            rest.exchange(
                    "/api/staff/estamp",
                    HttpMethod.POST,
                    new HttpEntity<>(form, headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private ResponseEntity<String> progress(UUID agreementId, String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(
        "/api/signing/" + agreementId + "/progress",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);
  }

  private void claim(UUID agreementId, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    assertThat(
            rest.exchange(
                    "/api/agreements/" + agreementId + "/claim",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class)
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  private static String hmacSha1Hex(String data, String key) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : raw) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // --- tests -----------------------------------------------------------------

  @Test
  void progressShowsEachPartysOwnStatusAlongsideTheAggregate() {
    UUID agreementId = createSignRequestedAgreement("DOC-PROG-1");
    // The owner has signed; the tenant has not. The aggregate alone ("in progress") would hide
    // exactly the fact the customer wants to know.
    WIREMOCK.stubFor(
        get(urlPathEqualTo(DETAILS_URL))
            .willReturn(
                okJson(
                    "{\"data\":{\"invitees\":[{\"inviteeId\":\"INV-1\",\"status\":\"SIGNED\"},"
                        + "{\"inviteeId\":\"INV-2\",\"status\":\"SENT\"}]}}")));
    HttpHeaders webhookHeaders = new HttpHeaders();
    webhookHeaders.setContentType(MediaType.APPLICATION_JSON);
    rest.postForEntity(
        "/api/webhooks/esign",
        new HttpEntity<>(
            "{\"documentId\":\"DOC-PROG-1\",\"mac\":\""
                + hmacSha1Hex("DOC-PROG-1", MAC_KEY)
                + "\"}",
            webhookHeaders),
        String.class);

    ResponseEntity<String> response = progress(agreementId, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"IN_PROGRESS\"");
    assertThat(response.getBody()).contains("\"role\":\"OWNER\"");
    assertThat(response.getBody()).contains("\"role\":\"TENANT\"");
    assertThat(response.getBody()).contains("SIGNED");
    assertThat(response.getBody()).contains("PENDING");
  }

  @Test
  void progressExposesNoSigningUrlNoSignerPiiAndNoProviderCredential() {
    UUID agreementId = createSignRequestedAgreement("DOC-PROG-2");

    String body = progress(agreementId, null).getBody();

    // Signing URLs are bearer capabilities belonging to ONE party each.
    assertThat(body).doesNotContain("OWNERSECRET");
    assertThat(body).doesNotContain("TENANTSECRET");
    // No party PII: the view identifies signers by id and role, nothing else.
    assertThat(body).doesNotContain("asha@example.com");
    assertThat(body).doesNotContain("tara@example.com");
    assertThat(body).doesNotContain("Asha");
    assertThat(body).doesNotContain("Ravi Owner");
    // No provider credential, and not even the provider's document id.
    assertThat(body).doesNotContain("it-auth-token");
    assertThat(body).doesNotContain("DOC-PROG-2");
  }

  @Test
  void anOwnedAgreementsProgressIsNotReadableByAnotherCustomer() {
    UUID agreementId = createSignRequestedAgreement("DOC-PROG-3");
    claim(agreementId, ownerToken);

    assertThat(progress(agreementId, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    // A different customer gets the same 404 as an unknown id - ownership cannot be probed.
    assertThat(progress(agreementId, strangerToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(progress(agreementId, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void staffCanReadProgressForAnyAgreement() {
    UUID agreementId = createSignRequestedAgreement("DOC-PROG-4");
    claim(agreementId, ownerToken);

    assertThat(progress(agreementId, staffToken).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void anUnknownAgreementIsA404() {
    assertThat(progress(UUID.randomUUID(), staffToken).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void everyPartyReadsAsPendingBeforeAnybodySigns() {
    UUID agreementId = createSignRequestedAgreement("DOC-PROG-5");

    String body = progress(agreementId, null).getBody();

    assertThat(body).contains("\"status\":\"IN_PROGRESS\"");
    assertThat(body).doesNotContain("\"status\":\"SIGNED\"");
  }
}
