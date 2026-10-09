package in.agreementmitra.signing.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.TemplateCatalogApi;
import in.agreementmitra.documents.api.TemplateFormApi;
import in.agreementmitra.documents.api.TemplateSummary;
import in.agreementmitra.support.HarnessTestConfig;
import in.agreementmitra.support.TemplateParity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guard for the parity contract from the {@code signing} side: every field a published production
 * form schema marks {@code required} with no default must be a key {@link
 * AgreementDocumentMapper#toTemplateData} emits, or a user-answered field the capture form asks in
 * a mandatory section ({@link TemplateParity}). Otherwise a generate fed from the aggregate trips a
 * missing-required error the user could never clear -- or, as with the party father's name and
 * address before capture-required-fields-drift, the capture form and the server disagree on what is
 * mandatory.
 *
 * <p>It also pins {@link TemplateParity#AGGREGATE_KEYS} to the live mapper, so the shared copy the
 * documents-side guards read can never become the authority. Its required set comes from the
 * projected schema, so a required field listed in NO section is invisible here -- only the
 * documents-side guards enforce that branch. It reads the schema through the public {@link
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
  void everyRequiredFieldWithoutADefaultIsSuppliedByTheMapperOrAskedOfTheUser() {
    Set<String> mapped = AgreementDocumentMapper.toTemplateData(anAgreement()).keySet();
    assertThat(TemplateParity.AGGREGATE_KEYS).isEqualTo(mapped);

    // Every published (state, type), read from the catalog rather than copied here, so a newly
    // published state is guarded without touching this test.
    List<TemplateSummary> published = templateCatalog.list(null, null, null);
    assertThat(published)
        .extracting(t -> t.state() + "|" + t.type())
        .contains("IN|residential", "TG|residential", "KA|residential", "TG|commercial");

    for (TemplateSummary template : published) {
      FormSchema schema = templateForm.formFor(template.state(), template.type());
      Set<String> requiredUndefaulted =
          schema.sections().stream()
              .flatMap(section -> section.fields().stream())
              .filter(field -> field.required() && field.defaultValue() == null)
              .map(FormField::key)
              .collect(Collectors.toSet());
      assertThat(TemplateParity.violations(schema, requiredUndefaulted, mapped))
          .as("%s/%s parity", template.state(), template.type())
          .isEmpty();
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
