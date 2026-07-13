package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Records the selected catalog template's id on the agreement (design D5 / the MODIFIED
 * agreement-management requirement) against real Postgres. A selection sets the shared {@code
 * template_id} column (added here as the catalog-lands-first fallback) server-side; an agreement
 * with no selection keeps it null and the effective-template hash pin remains deferred. The value
 * is a plain {@link UUID} -- the aggregate carries no {@code documents} type. {@code
 * disabledWithoutDocker = true} makes it skip (not fail) without Docker.
 */
@SpringBootTest
@Import(HarnessTestConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AgreementTemplateSelectionIntegrationTest {

  @Autowired private AgreementRepository repository;
  @Autowired private AgreementService service;
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

  private UUID persistedTemplateId(UUID agreementId) {
    return jdbc.queryForObject(
        "SELECT template_id FROM agreement WHERE id = ?", UUID.class, agreementId);
  }

  @Test
  void selectionRecordsTemplateIdServerSide() {
    UUID agreementId = newDraft().getId();
    UUID templateId = UUID.randomUUID();

    service.recordSelectedTemplate(agreementId, templateId);

    assertThat(persistedTemplateId(agreementId)).isEqualTo(templateId);
  }

  @Test
  void draftWithoutSelectionKeepsTemplateIdNull() {
    UUID agreementId = newDraft().getId();
    assertThat(persistedTemplateId(agreementId)).isNull();
  }
}
