package in.agreementmitra.signing.api;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.agreement.AgreementRepository;
import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the agreement endpoints through the <em>real</em> security filter chain (full-context,
 * RANDOM_PORT, {@link TestRestTemplate}) — not a filter-blind MockMvc slice. This is the
 * authoritative proof that the default-deny baseline permits {@code /api/agreements/**} (D6): a
 * MockMvc slice would pass even if the permit rule were missing.
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementEndpointIntegrationTest {

  @Autowired private TestRestTemplate rest;
  @Autowired private AgreementRepository repository;

  private static CreateAgreementRequest valid() {
    return new CreateAgreementRequest(
        "Asha Landlord",
        "Ravi Tenant",
        "12 MG Road, Bengaluru 560001",
        new BigDecimal("25000.00"),
        new BigDecimal("50000.00"),
        11);
  }

  @Test
  void createValidReturns201WithBodyAndPersistsRow() {
    long before = repository.count();

    ResponseEntity<AgreementResponse> resp =
        rest.postForEntity("/api/agreements", valid(), AgreementResponse.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(201);
    AgreementResponse body = resp.getBody();
    assertThat(body).isNotNull();
    assertThat(body.id()).isNotNull();
    assertThat(body.createdAt()).isNotNull();
    assertThat(body.landlordName()).isEqualTo("Asha Landlord");
    assertThat(body.tenantName()).isEqualTo("Ravi Tenant");
    assertThat(body.propertyAddress()).isEqualTo("12 MG Road, Bengaluru 560001");
    assertThat(body.monthlyRent()).isEqualByComparingTo("25000.00");
    assertThat(body.securityDeposit()).isEqualByComparingTo("50000.00");
    assertThat(body.termMonths()).isEqualTo(11);
    // 201 reaching the controller proves the default-deny baseline did not 401/403 the write.
    assertThat(repository.existsById(body.id())).isTrue();
    assertThat(repository.count()).isEqualTo(before + 1);
  }

  @Test
  void invalidBodyReturns400AndPersistsNoRowWithoutLeakingInternals() {
    long before = repository.count();
    CreateAgreementRequest invalid =
        new CreateAgreementRequest(
            "  ", "Ravi", "Addr", new BigDecimal("-1"), new BigDecimal("1"), 0);

    ResponseEntity<String> resp = rest.postForEntity("/api/agreements", invalid, String.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(400);
    assertThat(repository.count()).isEqualTo(before);
    // Error body must not leak stack traces or internal class names (server.error.* pin this).
    String error = resp.getBody() == null ? "" : resp.getBody();
    assertThat(error).doesNotContain("in.agreementmitra").doesNotContain("Exception");
  }

  @Test
  void getExistingReturns200() {
    UUID id =
        rest.postForEntity("/api/agreements", valid(), AgreementResponse.class).getBody().id();

    ResponseEntity<AgreementResponse> resp =
        rest.getForEntity("/api/agreements/" + id, AgreementResponse.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(resp.getBody()).isNotNull();
    assertThat(resp.getBody().id()).isEqualTo(id);
  }

  @Test
  void getUnknownIdReturns404() {
    ResponseEntity<String> resp =
        rest.getForEntity("/api/agreements/" + UUID.randomUUID(), String.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(404);
  }
}
