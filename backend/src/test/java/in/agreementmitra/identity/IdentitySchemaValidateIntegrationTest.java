package in.agreementmitra.identity;

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
 * Boot-and-validate for the identity schema (task 5.6): the context starts against V1..V11 with JPA
 * {@code ddl-auto: validate}, which passes only if every new {@code @Entity} mapping ({@code
 * Identity}, {@code IdentityCredential}, {@code AuthSession}, {@code OauthLoginState}, {@code
 * LoginHandoff}) matches the Flyway-created columns. Reaching the test body proves the boot; the
 * assertions pin V11 as applied and the five tables as present.
 *
 * <p>{@code disabledWithoutDocker = true} skips (not fails) without a Docker daemon, consistent
 * with the rest of the harness.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdentitySchemaValidateIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void v11IdentityMigrationIsApplied() {
    Integer applied =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '11' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);
  }

  @Test
  void allFiveIdentityTablesExist() {
    for (String table :
        new String[] {
          "identity", "identity_credential", "auth_session", "oauth_login_state", "login_handoff"
        }) {
      Integer count =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
              Integer.class,
              table);
      assertThat(count).as("table %s exists", table).isEqualTo(1);
    }
  }
}
