package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.TemplateCatalogFixture;
import in.agreementmitra.support.TestPdfs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The jurisdiction gate, from the outside.
 *
 * <p>What is being proved: an agreement in a jurisdiction we cannot stamp <b>never reaches a step
 * that commits us to something real</b>. Stamp duty is state law, so such an agreement has no
 * computable duty and no defined state in which staff could buy a certificate - and the published
 * terms guarantee no second bill after payment, so charging for it would be a liability against a
 * fulfilment path that does not exist.
 *
 * <p>The refusal is deliberately checked to be a <b>distinct</b> problem type, not the
 * payment-required one: an operator reading a 409 must be able to tell which precondition stopped
 * the pipeline, because a different person resolves each.
 *
 * <p>Equally important, and asserted here: the national template stays <b>fully usable for
 * drafting</b>. The gate restricts paid fulfilment only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class JurisdictionGateIntegrationTest {

  private static final String JURISDICTION_PROBLEM = "jurisdiction-unsupported";

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("delivery.public-base-url", () -> "https://app.example.test");
    registry.add("delivery.channels.email.enabled", () -> "true");
    // The shipped default, stated explicitly so this test does not depend on application.yml.
    registry.add("jurisdiction.eligible", () -> "TG");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  // --- helpers ---------------------------------------------------------------

  /** Create an agreement pinned to {@code state}, seeding that state's catalog row first. */
  private UUID agreementIn(String state) {
    TemplateCatalogFixture.seed(jdbc, state);
    Map<String, Object> body =
        Map.of(
            "state", state,
            "type", TemplateCatalogFixture.TYPE,
            "propertyAddress", "12 MG Road, Hyderabad",
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
                        "fatherName", "Vikram Tenant",
                        "currentAddress", "2 B St",
                        "email", "tara@example.com",
                        "role", "TENANT")));
    ResponseEntity<Map<String, Object>> created =
        rest.exchange(
            "/api/agreements",
            HttpMethod.POST,
            new HttpEntity<>(body, json()),
            new org.springframework.core.ParameterizedTypeReference<>() {});
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(String.valueOf(created.getBody().get("id")));
  }

  /** Create an agreement with NO dimensions, so nothing pins it to a jurisdiction. */
  private UUID bareAgreement() {
    Map<String, Object> bare =
        Map.of(
            "propertyAddress", "9 Bare St",
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
                        "fatherName", "Vikram Tenant",
                        "currentAddress", "2 B St",
                        "email", "tara@example.com",
                        "role", "TENANT")));
    ResponseEntity<Map<String, Object>> created =
        rest.exchange(
            "/api/agreements",
            HttpMethod.POST,
            new HttpEntity<>(bare, json()),
            new org.springframework.core.ParameterizedTypeReference<>() {});
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString(String.valueOf(created.getBody().get("id")));
  }

  private void attachDraft(UUID id) {
    LinkedMultiValueMapHolder.attach(rest, id);
  }

  private static HttpHeaders json() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private ResponseEntity<String> post(String path, UUID id) {
    return rest.exchange(
        path.replace("{id}", id.toString()),
        HttpMethod.POST,
        new HttpEntity<>(json()),
        String.class);
  }

  // --- the customer path -----------------------------------------------------

  @Test
  @DisplayName("finalise is refused for the national jurisdiction, and places no order")
  void finaliseRefusesNational() {
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);

    ResponseEntity<String> refused = post("/api/agreements/{id}/finalise", id);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains(JURISDICTION_PROBLEM);
    // Distinct from payment-required: a different person fixes each.
    assertThat(refused.getBody()).doesNotContain("payment-required");
    // No signing request was created, so nothing reached the staff stamp queue.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signing_request WHERE agreement_id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  @DisplayName("checkout is refused for the national jurisdiction, and creates no order")
  void checkoutRefusesNational() {
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);

    ResponseEntity<String> refused = post("/api/agreements/{id}/payment/order", id);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains(JURISDICTION_PROBLEM);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE agreement_id = ?", Integer.class, id))
        .isZero();
  }

  @Test
  @DisplayName("the refusal names the jurisdiction and the eligible ones, and carries no party PII")
  void refusalIsActionableAndCarriesNoPii() {
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);

    ResponseEntity<String> refused = post("/api/agreements/{id}/finalise", id);
    String body = refused.getBody();

    assertThat(body).contains("\"jurisdiction\":\"IN\"");
    assertThat(body).contains("TG");
    // Error bodies never echo submitted values or PII.
    assertThat(body).doesNotContain("Asha").doesNotContain("asha@example.com");
    assertThat(body).doesNotContain("Tara").doesNotContain("MG Road");
  }

  @Test
  @DisplayName("an agreement with NO pinned template is refused too: unknown is not fine")
  void dimensionlessAgreementIsRefused() {
    // The accepted breaking change. state/type are documented optional at create, so this used to
    // be a working path all the way to paid fulfilment.
    UUID id = bareAgreement();
    attachDraft(id);

    ResponseEntity<String> refused = post("/api/agreements/{id}/finalise", id);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains(JURISDICTION_PROBLEM);
  }

  // --- the disclosure reads the agreement's own jurisdiction ------------------

  @Test
  @DisplayName("the agreement response carries its pinned jurisdiction, so the SPA need not guess")
  void agreementResponseCarriesItsJurisdiction() {
    // Without this the SPA has nothing to mark a REOPENED agreement from: its capture shell
    // defaults the state prop, so every recovery link and resumed draft rendered the draft-only
    // banner -- including a stampable one. Server-derived from the pinned template, never echoed
    // from what the client sent at create.
    UUID id = agreementIn(TemplateCatalogFixture.ELIGIBLE_STATE);

    ResponseEntity<Map<String, Object>> fetched =
        rest.exchange(
            "/api/agreements/{id}",
            HttpMethod.GET,
            new HttpEntity<>(json()),
            new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {},
            id);

    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).containsEntry("state", TemplateCatalogFixture.ELIGIBLE_STATE);
    assertThat(fetched.getBody()).containsEntry("type", TemplateCatalogFixture.TYPE);
    // The template ID itself stays inside signing: only the dimensions are disclosed.
    assertThat(fetched.getBody()).doesNotContainKey("templateId");
  }

  @Test
  @DisplayName(
      "an agreement with no pinned template reports no jurisdiction, rather than a default")
  void agreementResponseReportsNoJurisdictionWhenUnpinned() {
    // Must agree with the gate: this is the agreement the gate refuses as an unknown jurisdiction,
    // so the response must not hand the client a state that would make it look fulfillable.
    UUID id = bareAgreement();

    ResponseEntity<Map<String, Object>> fetched =
        rest.exchange(
            "/api/agreements/{id}",
            HttpMethod.GET,
            new HttpEntity<>(json()),
            new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {},
            id);

    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody().get("state")).isNull();
    assertThat(fetched.getBody().get("type")).isNull();
  }

  // --- the eligible path still works ----------------------------------------

  @Test
  @DisplayName("an eligible jurisdiction passes finalise unchanged, and stays idempotent")
  void eligiblePassesAndStaysIdempotent() {
    UUID id = agreementIn(TemplateCatalogFixture.ELIGIBLE_STATE);
    attachDraft(id);

    ResponseEntity<String> first = post("/api/agreements/{id}/finalise", id);
    ResponseEntity<String> second = post("/api/agreements/{id}/finalise", id);

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signing_request WHERE agreement_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  // --- drafting is NOT restricted -------------------------------------------

  @Test
  @DisplayName("the national template is still fully usable to draft, generate and preview")
  void nationalRemainsDraftable() {
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);

    ResponseEntity<String> generated = post("/api/agreements/{id}/document", id);
    ResponseEntity<byte[]> preview =
        rest.exchange("/api/agreements/" + id + "/preview", HttpMethod.GET, null, byte[].class);

    assertThat(generated.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(preview.getBody()).isNotEmpty();
  }

  // --- the disclosure endpoint ----------------------------------------------

  @Test
  @DisplayName("the eligible-jurisdictions list is anonymous and carries no agreement data")
  void eligibleListIsAnonymousAndStatic() {
    ResponseEntity<String> res = rest.getForEntity("/api/jurisdictions", String.class);

    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(res.getBody()).contains("TG");
    assertThat(res.getBody()).doesNotContain("IN\"");
  }

  /** Attaching a draft PDF, kept out of the test bodies so they read as the rule they prove. */
  private static final class LinkedMultiValueMapHolder {
    static void attach(TestRestTemplate rest, UUID id) {
      org.springframework.util.LinkedMultiValueMap<String, Object> form =
          new org.springframework.util.LinkedMultiValueMap<>();
      form.add(
          "file",
          new org.springframework.core.io.ByteArrayResource(TestPdfs.singlePage()) {
            @Override
            public String getFilename() {
              return "draft.pdf";
            }
          });
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.MULTIPART_FORM_DATA);
      rest.exchange(
          "/api/agreements/" + id + "/draft",
          HttpMethod.POST,
          new HttpEntity<>(form, headers),
          String.class);
    }
  }

  /**
   * Insert a payment order directly. A fixture, not an assertion: driving a real settlement through
   * the provider would turn a test about the jurisdiction rule into a test about Razorpay.
   */
  private void seedOrder(UUID agreementId, String status) {
    jdbc.update(
        "INSERT INTO payment_order (id, agreement_id, provider, provider_order_id, receipt,"
            + " amount_minor_units, currency, status, created_at, version)"
            + " VALUES (?,?,?,?,?,?,?,?,?,0)",
        UUID.randomUUID(),
        agreementId,
        "RAZORPAY",
        "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18),
        agreementId.toString(),
        49900L,
        "INR",
        status,
        java.sql.Timestamp.from(java.time.Instant.now()));
  }

  @Test
  @DisplayName("an OUTSTANDING order is not resumed once the jurisdiction is ineligible")
  void outstandingOrderIsNotResumed() {
    // An order placed earlier proves the jurisdiction was eligible THEN, not now: configuration can
    // change under an unpaid order, so the reuse path is re-checked rather than trusted.
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);
    seedOrder(id, "CREATED");

    ResponseEntity<String> refused = post("/api/agreements/{id}/payment/order", id);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains(JURISDICTION_PROBLEM);
  }

  @Test
  @DisplayName("a SETTLED order is still reported, never refused -- the hazard inverted")
  void settledOrderIsStillReported() {
    // THE case this gate must not break. Refusing here would 409 a customer who has already paid,
    // recreating the exact "no second bill" problem the jurisdiction gate exists to prevent -- and
    // it is why the check sits AFTER the settled branch rather than where reachability sits.
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);
    seedOrder(id, "PAID");

    ResponseEntity<String> resumed = post("/api/agreements/{id}/payment/order", id);

    assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resumed.getBody()).doesNotContain(JURISDICTION_PROBLEM);
  }

  @Test
  @DisplayName("eSign initiation is refused, and a staff WAIVER does not open the door")
  void esignInitiationRefusedEvenWhenWaived() {
    // PaymentGate is the only other control on this step, and a waiver satisfies it -- so without
    // its own check this would be reachable for an agreement we cannot stamp.
    UUID id = agreementIn(TemplateCatalogFixture.NATIONAL_STATE);
    attachDraft(id);
    in.agreementmitra.support.Payments.waive(jdbc, id);

    ResponseEntity<String> refused =
        rest.exchange(
            "/api/signing/" + id + "/request",
            HttpMethod.POST,
            new HttpEntity<>(json()),
            String.class);

    assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(refused.getBody()).contains(JURISDICTION_PROBLEM);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM signing_request WHERE agreement_id = ? AND status ="
                    + " 'SIGN_REQUESTED'",
                Integer.class,
                id))
        .isZero();
  }
}
