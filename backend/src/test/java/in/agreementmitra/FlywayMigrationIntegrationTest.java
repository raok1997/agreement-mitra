package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the persistence foundation works against real infra: the context boots with Flyway
 * provisioning the schema and JPA {@code ddl-auto: validate} (no {@code create-drop}), and the V1
 * baseline migration is recorded as applied.
 *
 * <p>The {@code flyway_schema_history} assertion is the primary acceptance check — it proves Flyway
 * actually ran against the Testcontainers Postgres via the real migration path. Reaching the test
 * body at all proves the context started under {@code validate} (a broken migration or a failed
 * schema validation would prevent context load).
 *
 * <p>{@code disabledWithoutDocker = true} makes this skip (not fail) without a Docker daemon,
 * consistent with the rest of the harness.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v1BaselineMigrationIsRecordedAsApplied() {
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
            Integer.class);

    assertThat(applied).isEqualTo(1);
  }

  @Test
  void contextBootsUnderFlywayManagedSchemaAndValidate() {
    // Reaching here means the context loaded with Flyway-applied migrations and ddl-auto: validate
    // (the test profile no longer uses create-drop). Confirm the history table itself exists.
    Integer historyTables =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'flyway_schema_history'",
            Integer.class);

    assertThat(historyTables).isEqualTo(1);
  }
}
