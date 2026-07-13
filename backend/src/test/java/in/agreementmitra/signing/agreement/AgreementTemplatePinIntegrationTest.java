package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the reproducibility-pin persistence against real Postgres: booting the context proves
 * the forward-only {@code V9__agreement_template_pin.sql} migration applies and JPA {@code
 * ddl-auto: validate} passes against the two new columns, and the round-trip proves the {@code
 * Agreement} mapping (String {@code template_content_hash}; {@code Map<String,Integer>} pinned to
 * the {@code jsonb} {@code template_layer_versions}) reads and writes correctly. {@code
 * disabledWithoutDocker = true} makes it skip (not fail) without Docker.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementTemplatePinIntegrationTest {

  @Autowired private AgreementRepository repository;
  @Autowired private JdbcTemplate jdbc;

  private Agreement newDraft() {
    Agreement a =
        Agreement.create(
            "12 MG Road, Bengaluru",
            new BigDecimal("25000.00"),
            new BigDecimal("50000.00"),
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-01"));
    return repository.save(a);
  }

  @Test
  void pinRoundTripsThroughPostgresIncludingTheJsonbLayerVersions() {
    UUID id = newDraft().getId();

    Agreement pinned = repository.findById(id).orElseThrow();
    pinned.pinEffectiveTemplate("sha256:abc123", Map.of("base", 3, "in-residential", 1));
    repository.save(pinned);

    // Persisted column values (jsonb reads back as its JSON text through JdbcTemplate).
    assertThat(
            jdbc.queryForObject(
                "SELECT template_content_hash FROM agreement WHERE id = ?", String.class, id))
        .isEqualTo("sha256:abc123");
    assertThat(
            jdbc.queryForObject(
                "SELECT template_layer_versions FROM agreement WHERE id = ?", String.class, id))
        .contains("\"base\": 3", "\"in-residential\": 1");

    // The JPA mapping deserializes the jsonb back to a typed map on reload.
    Agreement reloaded = repository.findById(id).orElseThrow();
    assertThat(reloaded.templateContentHash()).isEqualTo("sha256:abc123");
    assertThat(reloaded.templateLayerVersions())
        .containsExactlyInAnyOrderEntriesOf(Map.of("base", 3, "in-residential", 1));
  }

  @Test
  void ungeneratedAgreementHasNullPinColumns() {
    UUID id = newDraft().getId();

    assertThat(
            jdbc.queryForObject(
                "SELECT template_content_hash FROM agreement WHERE id = ?", String.class, id))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT template_layer_versions FROM agreement WHERE id = ?", String.class, id))
        .isNull();
  }
}
