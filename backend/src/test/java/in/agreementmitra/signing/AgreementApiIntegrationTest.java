package in.agreementmitra.signing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.signing.api.AgreementResponse;
import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full-pipeline integration test for the agreement API against real Postgres (Testcontainers): the
 * HTTP surface, the security permits, the service, the JPA mapping, and the Flyway V2 schema. Runs
 * over a real port via {@link TestRestTemplate}. {@code disabledWithoutDocker = true} makes it skip
 * (not fail) without a Docker daemon, consistent with the rest of the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementApiIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;

  private final ObjectMapper mapper = new ObjectMapper();

  private JsonNode assertProblemJson(ResponseEntity<String> resp, HttpStatus expected) {
    assertThat(resp.getStatusCode()).isEqualTo(expected);
    assertThat(resp.getHeaders().getContentType())
        .isNotNull()
        .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    try {
      JsonNode body = mapper.readTree(resp.getBody());
      assertThat(body.path("status").asInt()).isEqualTo(expected.value());
      return body;
    } catch (Exception e) {
      throw new AssertionError("response body is not valid JSON: " + resp.getBody(), e);
    }
  }

  private static void assertNoLeak(String body, String mustBeAbsent) {
    assertThat(body).doesNotContain(mustBeAbsent);
    // No stack trace / internals.
    assertThat(body).doesNotContain("Exception").doesNotContain("\tat ");
  }

  private static Map<String, Object> signer(String name, String email, String role) {
    return Map.of("name", name, "email", email, "role", role);
  }

  private Map<String, Object> validBody(List<Map<String, Object>> signers) {
    return Map.of(
        "propertyAddress",
        "12 MG Road, Bengaluru",
        "monthlyRent",
        new BigDecimal("25000.00"),
        "securityDeposit",
        new BigDecimal("50000.00"),
        "termMonths",
        11,
        "signers",
        signers);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  @Test
  void createPersistsAgreementAndSignersThenGetRoundTrips() {
    Map<String, Object> body =
        validBody(
            List.of(
                signer("Asha Owner", "asha@example.com", "OWNER"),
                signer("Bhanu Owner", "bhanu@example.com", "OWNER"),
                signer("Tara Tenant", "tara@example.com", "TENANT")));

    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", body, AgreementResponse.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    AgreementResponse agreement = created.getBody();
    assertThat(agreement).isNotNull();
    assertThat(agreement.id()).isNotNull();
    assertThat(agreement.createdAt()).isNotNull();
    assertThat(agreement.signers()).hasSize(3);
    assertThat(agreement.signers()).allSatisfy(s -> assertThat(s.id()).isNotNull());

    Long agreements =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM agreement WHERE id = ?", Long.class, agreement.id());
    assertThat(agreements).isEqualTo(1);
    Long signers =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM signer WHERE agreement_id = ?", Long.class, agreement.id());
    assertThat(signers).isEqualTo(3);

    ResponseEntity<AgreementResponse> fetched =
        rest.getForEntity("/api/agreements/" + agreement.id(), AgreementResponse.class);
    assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(fetched.getBody()).isNotNull();
    assertThat(fetched.getBody().signers()).hasSize(3);
    assertThat(fetched.getBody().signers())
        .extracting(AgreementResponse.SignerResponse::email)
        .containsExactlyInAnyOrder("asha@example.com", "bhanu@example.com", "tara@example.com");
  }

  @Test
  void duplicateEmailIsRejectedWith400AndPersistsNothing() {
    long agreementsBefore = count("agreement");
    long signersBefore = count("signer");
    Map<String, Object> body =
        validBody(
            List.of(
                signer("Asha Owner", "dup@example.com", "OWNER"),
                signer("Tara Tenant", "dup@example.com", "TENANT")));

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", body, String.class);

    JsonNode problem = assertProblemJson(resp, HttpStatus.BAD_REQUEST);
    assertThat(problem.path("errors")).isNotEmpty();
    // Cross-field rule: a duplicate email must not be echoed back in the error body.
    assertNoLeak(resp.getBody(), "dup@example.com");
    assertThat(count("agreement")).isEqualTo(agreementsBefore);
    assertThat(count("signer")).isEqualTo(signersBefore);
  }

  @Test
  void clientSuppliedIdAndCreatedAtAreIgnored() {
    UUID clientId = UUID.randomUUID();
    Map<String, Object> body =
        new java.util.HashMap<>(
            validBody(
                List.of(
                    signer("Asha Owner", "asha-ignore@example.com", "OWNER"),
                    signer("Tara Tenant", "tara-ignore@example.com", "TENANT"))));
    body.put("id", clientId.toString());
    body.put("createdAt", "2000-01-01T00:00:00Z");

    ResponseEntity<AgreementResponse> created =
        rest.postForEntity("/api/agreements", body, AgreementResponse.class);

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(created.getBody()).isNotNull();
    // Server assigns its own id + timestamp; the client's values are dropped, not honoured.
    assertThat(created.getBody().id()).isNotEqualTo(clientId);
    assertThat(created.getBody().createdAt())
        .isAfter(java.time.Instant.parse("2020-01-01T00:00:00Z"));
  }

  @Test
  void missingRoleIsRejectedWith400() {
    Map<String, Object> body =
        validBody(
            List.of(
                signer("Asha Owner", "asha2@example.com", "OWNER"),
                signer("Bhanu Owner", "bhanu2@example.com", "OWNER")));

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", body, String.class);

    JsonNode problem = assertProblemJson(resp, HttpStatus.BAD_REQUEST);
    // Cross-field signer-set rule maps to the stable "signers" field token.
    assertThat(problem.path("errors"))
        .anySatisfy(e -> assertThat(e.path("field").asText()).isEqualTo("signers"));
  }

  @Test
  void invalidEmailIsRejectedWith400AndDoesNotEchoEmail() {
    String badEmail = "not-a-valid-email-value";
    Map<String, Object> body =
        validBody(
            List.of(
                signer("Asha Owner", badEmail, "OWNER"),
                signer("Tara Tenant", "tara-ok@example.com", "TENANT")));

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", body, String.class);

    assertProblemJson(resp, HttpStatus.BAD_REQUEST);
    assertNoLeak(resp.getBody(), badEmail);
  }

  @Test
  void unknownIdReturns404ProblemJsonWithoutReflectingId() {
    UUID missing = UUID.randomUUID();

    ResponseEntity<String> resp = rest.getForEntity("/api/agreements/" + missing, String.class);

    assertProblemJson(resp, HttpStatus.NOT_FOUND);
    // Constant detail — the requested id must not be reflected.
    assertNoLeak(resp.getBody(), missing.toString());
  }

  @Test
  void nonUuidIdIsRejectedWith400AndDoesNotEchoValue() {
    ResponseEntity<String> resp = rest.getForEntity("/api/agreements/not-a-uuid", String.class);

    assertProblemJson(resp, HttpStatus.BAD_REQUEST);
    assertNoLeak(resp.getBody(), "not-a-uuid");
  }

  @Test
  void malformedJsonIsRejectedWith400WithoutEchoingPayload() {
    String embeddedValue = "secret-leak-value";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    // Missing closing brace → unparseable; carries a PII-looking value that must not be echoed.
    HttpEntity<String> request =
        new HttpEntity<>("{\"propertyAddress\":\"" + embeddedValue + "\"", headers);

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", request, String.class);

    assertProblemJson(resp, HttpStatus.BAD_REQUEST);
    assertNoLeak(resp.getBody(), embeddedValue);
  }

  @Test
  void flywayV2AgreementMigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
            Integer.class);

    assertThat(applied).isEqualTo(1);
  }
}
