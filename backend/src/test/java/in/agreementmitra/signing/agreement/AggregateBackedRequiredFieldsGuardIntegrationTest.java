package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateFormApi;
import in.agreementmitra.documents.api.TemplateSummary;
import in.agreementmitra.support.HarnessTestConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guard for the parity contract from the {@code signing} side: every field a published production
 * form schema marks {@code required} with no default must be a key {@link
 * AgreementDocumentMapper#toTemplateData} emits. Otherwise a generate fed from the aggregate trips
 * a missing-required error -- or, as with the party father's name and address before
 * capture-required-fields-drift, the capture form and the server disagree on what is mandatory.
 *
 * <p>The aggregate-backed key list is copied into the mapper javadoc, the layer-set test helpers
 * and the set YAML headers; this test is what makes the next required field the mapper does not
 * supply fail in one place instead of drifting. It reads the schema through the public {@link
 * TemplateFormApi} under {@code test,sandbox} -- the plain {@code test} profile resolves the
 * fixture set, not the production one. Skips (not fails) without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HarnessTestConfig.class)
@ActiveProfiles({"test", "sandbox"})
@Testcontainers(disabledWithoutDocker = true)
class AggregateBackedRequiredFieldsGuardIntegrationTest {

  @Autowired private TemplateCatalogApi templateCatalog;
  @Autowired private TemplateFormApi templateForm;

  @Test
  void everyRequiredFieldWithoutADefaultIsSuppliedByTheMapper() {
    Set<String> mapped = AgreementDocumentMapper.toTemplateData(anAgreement()).keySet();

    // Every published (state, type), read from the catalog rather than copied here, so a newly
    // published state is guarded without touching this test.
    List<TemplateSummary> published = templateCatalog.list(null, null, null);
    assertThat(published)
        .extracting(t -> t.state() + "|" + t.type())
        .contains("IN|residential", "TG|residential", "KA|residential", "TG|commercial");

    for (TemplateSummary template : published) {
      FormSchema schema = templateForm.formFor(template.state(), template.type());
      for (FormSection section : schema.sections()) {
        for (FormField field : section.fields()) {
          if (field.required() && field.defaultValue() == null) {
            assertThat(mapped)
                .as(
                    "%s/%s required field '%s' is aggregate-backed",
                    template.state(), template.type(), field.key())
                .contains(field.key());
          }
        }
      }
    }
  }

  private static Agreement anAgreement() {
    Agreement agreement =
        Agreement.create(
            "12 MG Road",
            new BigDecimal("10000.00"),
            new BigDecimal("20000.00"),
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-01"));
    agreement.addSigner("Asha Owner", "Asha", "Owner", "Ravi", "1 St", null, null, Role.OWNER);
    agreement.addSigner(
        "Bhaskar Tenant", "Bhaskar", "Tenant", "Kiran", "2 St", null, null, Role.TENANT);
    return agreement;
  }
}
