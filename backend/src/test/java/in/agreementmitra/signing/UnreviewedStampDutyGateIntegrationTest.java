package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.GotenbergTestConfig;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The counsel gate, from the outside (state-stamp-duty-quoting, stamp-duty-rules spec). The shipped
 * Telangana rules carry no counsel review, so with {@code rules.stamp-duty.allow-unreviewed=false}
 * -- the production default -- a Telangana agreement must never reach paid fulfilment, while
 * drafting is untouched. The allowed path is covered by every other fulfilment test (the test
 * profile allows unreviewed rules, as local and beta deployments do).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, GotenbergTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UnreviewedStampDutyGateIntegrationTest {

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("rules.stamp-duty.allow-unreviewed", () -> "false");
    registry.add("delivery.public-base-url", () -> "https://app.example.test");
    registry.add("delivery.channels.email.enabled", () -> "true");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    in.agreementmitra.support.TemplateCatalogFixture.seedEligible(jdbc);
  }

  private UUID tgAgreement() {
    Map<String, Object> body =
        Map.of(
            "state", "TG",
            "type", "residential",
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
                        "fatherName", "Hari Tenant",
                        "currentAddress", "3 C St",
                        "email", "tara@example.com",
                        "role", "TENANT")));
    @SuppressWarnings("unchecked")
    ResponseEntity<Map> created = rest.postForEntity("/api/agreements", body, Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) created.getBody().get("id"));
  }

  @Test
  void theQuoteIsUnavailableBecauseTheRuleIsNotChargeable() {
    ResponseEntity<String> quote =
        rest.getForEntity("/api/agreements/" + tgAgreement() + "/stamp-quote", String.class);

    assertThat(quote.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(quote.getBody())
        .contains("\"available\":false")
        .contains("NOT_CHARGEABLE")
        .doesNotContain("dutyMinorUnits\":1");
  }

  /** Finalise refuses a missing draft before it looks at jurisdiction, so give it one. */
  private void uploadDraft(UUID agreementId) {
    org.springframework.util.LinkedMultiValueMap<String, Object> form =
        new org.springframework.util.LinkedMultiValueMap<>();
    form.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(
            in.agreementmitra.support.TestPdfs.singlePage()) {
          @Override
          public String getFilename() {
            return "draft.pdf";
          }
        });
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
    rest.exchange(
        "/api/agreements/" + agreementId + "/draft",
        org.springframework.http.HttpMethod.POST,
        new org.springframework.http.HttpEntity<>(form, headers),
        String.class);
  }

  @Test
  void checkoutAndFinaliseAreRefusedAsAnUnsupportedJurisdiction() {
    UUID agreementId = tgAgreement();
    uploadDraft(agreementId);

    ResponseEntity<String> checkout =
        rest.postForEntity(
            "/api/agreements/" + agreementId + "/payment/order",
            Map.of("stampValueMinorUnits", 130_000L),
            String.class);
    ResponseEntity<String> finalise =
        rest.postForEntity("/api/agreements/" + agreementId + "/finalise", null, String.class);

    assertThat(checkout.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(checkout.getBody()).contains("jurisdiction-unsupported");
    assertThat(finalise.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(finalise.getBody()).contains("jurisdiction-unsupported");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payment_order WHERE agreement_id = ?",
                Long.class,
                agreementId))
        .isZero();
  }

  @Test
  void thePickerListsNoChargeableJurisdiction() {
    ResponseEntity<String> list = rest.getForEntity("/api/jurisdictions", String.class);

    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).doesNotContain("TG");
  }
}
