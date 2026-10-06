package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.support.HarnessTestConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
  @Autowired private JdbcConnectionDetails connectionDetails;

  @Test
  void v22BackfillsOwnersFirstInStoredOrderAndLastEditedFromCreated() {
    // A scratch schema on its own unpooled connections: never SET search_path on the pooled
    // datasource, and drop it afterwards because other tests count information_schema rows
    // without a schema filter.
    String schema = "v22_backfill";
    DriverManagerDataSource scratch =
        new DriverManagerDataSource(
            connectionDetails.getJdbcUrl(),
            connectionDetails.getUsername(),
            connectionDetails.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(scratch);
    try {
      Flyway.configure().dataSource(scratch).schemas(schema).target("21").load().migrate();

      UUID agreementId = UUID.randomUUID();
      Timestamp created = Timestamp.from(Instant.parse("2026-01-02T03:04:05Z"));
      jdbc.update(
          "INSERT INTO "
              + schema
              + ".agreement (id, property_address, monthly_rent, security_deposit, term_months,"
              + " created_at, start_date, end_date, tracking_reference)"
              + " VALUES (?, '1 A St', 1000, 0, 11, ?, DATE '2026-01-01', DATE '2026-11-30',"
              + " 'AMBACKFILL1')",
          agreementId,
          created);
      // The tenant row is stored before both owner rows.
      for (String[] party :
          new String[][] {{"Tenant", "TENANT"}, {"Owner One", "OWNER"}, {"Owner Two", "OWNER"}}) {
        jdbc.update(
            "INSERT INTO "
                + schema
                + ".signer (id, agreement_id, name, first_name, last_name, father_name,"
                + " current_address, role) VALUES (?, ?, ?, ?, 'X', 'Father', '1 A St', ?)",
            UUID.randomUUID(),
            agreementId,
            party[0],
            party[0],
            party[1]);
      }

      Flyway.configure().dataSource(scratch).schemas(schema).target("22").load().migrate();

      List<String> byPosition =
          jdbc.queryForList(
              "SELECT name || ':' || entry_position FROM "
                  + schema
                  + ".signer WHERE agreement_id = ? ORDER BY entry_position",
              String.class,
              agreementId);
      assertThat(byPosition).containsExactly("Owner One:0", "Owner Two:1", "Tenant:2");
      Boolean editedFromCreated =
          jdbc.queryForObject(
              "SELECT last_edited_at = created_at FROM " + schema + ".agreement WHERE id = ?",
              Boolean.class,
              agreementId);
      assertThat(editedFromCreated).isTrue();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void v1BaselineMigrationIsRecordedAsApplied() {
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
            Integer.class);

    assertThat(applied).isEqualTo(1);
  }

  @Test
  void v16AddsTheWebhookKeyAndPaymentColumnsAndTheyValidateUnderJpa() {
    // Reaching this test at all proves ddl-auto: validate accepted the new mappings; these
    // assertions pin that the columns the ZOOP adapter and the payment gate depend on exist with
    // the right nullability, and that the unique payment reference is really indexed.
    Integer applied =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '16' AND success = true",
            Integer.class);
    assertThat(applied).isEqualTo(1);

    // The per-transaction webhook credential column: nullable, because a provider may issue none.
    String webhookKeyNullable =
        jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'signing_request' AND column_name = 'webhook_security_key'",
            String.class);
    assertThat(webhookKeyNullable).isEqualTo("YES");

    // Payment state is NOT NULL with a default, so every pre-existing row reads as UNPAID rather
    // than as an undefined fourth state.
    String paymentStateNullable =
        jdbcTemplate.queryForObject(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_name = 'agreement' AND column_name = 'payment_state'",
            String.class);
    assertThat(paymentStateNullable).isEqualTo("NO");

    Integer referenceIndex =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_agreement_payment_reference'",
            Integer.class);
    assertThat(referenceIndex).isEqualTo(1);
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

  @Test
  void v23LetsAnIntakeAuditRowOutliveItsDeletedDraft() {
    String deleteRule =
        jdbcTemplate.queryForObject(
            "SELECT delete_rule FROM information_schema.referential_constraints"
                + " WHERE constraint_schema = current_schema()"
                + " AND constraint_name = 'stamp_intake_audit_agreement_id_fkey'",
            String.class);

    assertThat(deleteRule).isEqualTo("SET NULL");
  }

  @Test
  void v25BackfillsOwnerDeleteAndAllowsAnOwnerlessPurgeRecordOnly() {
    String schema = "v25_reason";
    DriverManagerDataSource scratch =
        new DriverManagerDataSource(
            connectionDetails.getJdbcUrl(),
            connectionDetails.getUsername(),
            connectionDetails.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(scratch);
    String table = schema + ".agreement_deletion";
    try {
      Flyway.configure().dataSource(scratch).schemas(schema).target("24").load().migrate();
      UUID existing = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO "
              + table
              + " (agreement_id, tracking_reference, owner_identity_id, deleted_at)"
              + " VALUES (?, 'AMV25BACK1', ?, now())",
          existing,
          UUID.randomUUID());

      Flyway.configure().dataSource(scratch).schemas(schema).target("25").load().migrate();

      assertThat(
              jdbc.queryForObject(
                  "SELECT reason FROM " + table + " WHERE agreement_id = ?",
                  String.class,
                  existing))
          .isEqualTo("OWNER_DELETE");
      String insert =
          "INSERT INTO "
              + table
              + " (agreement_id, tracking_reference, owner_identity_id, deleted_at, reason)"
              + " VALUES (?, 'AMV25NEW01', NULL, now(), ?)";
      jdbc.update(insert, UUID.randomUUID(), "RETENTION_PURGE");
      assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "SOMETHING_ELSE"))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "INSERT INTO "
                          + table
                          + " (agreement_id, tracking_reference, owner_identity_id, deleted_at)"
                          + " VALUES (?, 'AMV25NEW02', ?, now())",
                      UUID.randomUUID(),
                      UUID.randomUUID()))
          .as("reason has no default after V25")
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }
}
