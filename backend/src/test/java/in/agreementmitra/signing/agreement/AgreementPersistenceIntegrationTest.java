package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Graduates the persistence-foundation transitional posture (D5): with a real mapped entity
 * present, the context boots under {@code ddl-auto: validate} against the {@code V2}-created
 * schema, and an Agreement round-trips through it unchanged. Reaching the test body proves {@code
 * validate} passed (a column/type/nullability drift between entity and migration fails context
 * startup).
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementPersistenceIntegrationTest {

  @Autowired private AgreementRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void flywayAppliedBothV1AndV2InOrder() {
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version IN ('1', '2') AND success = true",
            Integer.class);

    assertThat(applied).isEqualTo(2);
  }

  @Test
  void agreementRoundTripsThroughTheV2Schema() {
    Agreement saved =
        repository.saveAndFlush(
            Agreement.create(
                "Asha Landlord",
                "Ravi Tenant",
                "12 MG Road, Bengaluru 560001",
                new BigDecimal("25000.00"),
                new BigDecimal("50000.00"),
                11));

    // No @Transactional on the test → the save commits and this is a fresh read, a genuine
    // DB round-trip rather than a first-level-cache hit.
    Agreement loaded = repository.findById(saved.getId()).orElseThrow();

    assertThat(loaded.getId()).isEqualTo(saved.getId());
    assertThat(loaded.getLandlordName()).isEqualTo("Asha Landlord");
    assertThat(loaded.getTenantName()).isEqualTo("Ravi Tenant");
    assertThat(loaded.getPropertyAddress()).isEqualTo("12 MG Road, Bengaluru 560001");
    assertThat(loaded.getMonthlyRent()).isEqualByComparingTo("25000.00");
    assertThat(loaded.getSecurityDeposit()).isEqualByComparingTo("50000.00");
    assertThat(loaded.getTermMonths()).isEqualTo(11);
    // timestamptz keeps microseconds; allow sub-millisecond truncation on the round-trip.
    assertThat(loaded.getCreatedAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MILLIS));
  }
}
