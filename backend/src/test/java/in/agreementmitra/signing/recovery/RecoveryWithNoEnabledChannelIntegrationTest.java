package in.agreementmitra.signing.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.MailTestConfig;
import in.agreementmitra.support.Payments;
import in.agreementmitra.support.RecordingEmailSender;
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
 * Task 9.25: with <b>no</b> delivery channel enabled, the feature is inert -- nothing is
 * dispatched, and the responses are indistinguishable from a fully configured deployment.
 *
 * <p>Its own class because channel enablement is context configuration, not per-test state: the
 * sibling {@link RecoveryIntegrationTest} runs with email ON, and a test cannot turn it off inside
 * a shared context.
 *
 * <p><b>Why this matters beyond configuration hygiene.</b> The recovery endpoint's whole security
 * property is that every outcome answers identically, so a reference cannot be probed. A deployment
 * that has not configured a channel yet must not become the one case where the endpoint behaves
 * differently -- that would turn a misconfiguration into an enumeration oracle.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({HarnessTestConfig.class, MailTestConfig.class})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RecoveryWithNoEnabledChannelIntegrationTest {

  @DynamicPropertySource
  static void noChannelEnabled(DynamicPropertyRegistry registry) {
    registry.add("delivery.public-base-url", () -> "https://app.example.test");
    // The point of this class: every channel off.
    registry.add("delivery.channels.email.enabled", () -> "false");
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RecordingEmailSender mail;
  @Autowired private RecoveryRateLimiter rateLimiter;

  @BeforeEach
  void resetHarness() {
    mail.reset();
    rateLimiter.clear();
  }

  private record Created(UUID id, String reference) {}

  private Created createAgreement() {
    Map<String, Object> body =
        Map.of(
            "propertyAddress", "12 Test Street, Bengaluru 560038",
            "monthlyRent", "25000.00",
            "securityDeposit", "50000.00",
            "startDate", "2026-09-01",
            "endDate", "2027-07-31",
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
    Map<?, ?> payload = created.getBody();
    return new Created(
        UUID.fromString((String) payload.get("id")), (String) payload.get("trackingNumber"));
  }

  @Test
  void nothingIsDispatchedAndTheAnswerIsUnchanged() {
    Created paid = createAgreement();
    Payments.markPaid(jdbc, paid.id(), "pay_" + UUID.randomUUID());

    ResponseEntity<String> eligible =
        rest.postForEntity(
            "/api/agreements/recovery", Map.of("reference", paid.reference()), String.class);
    ResponseEntity<String> unknown =
        rest.postForEntity(
            "/api/agreements/recovery", Map.of("reference", "AMZZZZZZZZZ"), String.class);

    // Inert...
    assertThat(mail.sent()).isEmpty();

    // ...and still indistinguishable, which is the property that must not depend on configuration.
    assertThat(eligible.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(eligible.getBody()).isNull();
    assertThat(unknown.getStatusCode()).isEqualTo(eligible.getStatusCode());
    assertThat(unknown.getBody()).isEqualTo(eligible.getBody());
  }

  @Test
  void anEligibleAgreementIsStillAuditedEvenThoughNothingIsSent() {
    Created paid = createAgreement();
    Payments.markPaid(jdbc, paid.id(), "pay_" + UUID.randomUUID());

    rest.postForEntity(
        "/api/agreements/recovery", Map.of("reference", paid.reference()), String.class);

    // An operator asking "did we ever try to reach this customer?" must get an answer even when the
    // deployment could not have sent anything.
    Integer audited =
        jdbc.queryForObject(
            "SELECT count(*) FROM recovery_audit WHERE reference = ?",
            Integer.class,
            paid.reference());
    assertThat(audited).isPositive();
  }
}
