package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.documents.DocumentFooterProperties;
import in.agreementmitra.documents.api.DocumentDimensions;
import in.agreementmitra.documents.api.DocumentProjectionRequest;
import in.agreementmitra.documents.api.DocumentProjectionResult;
import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * System-sourced fields ({@code source: system}), change {@code
 * stamp-duty-amount-from-certificate}: the declaration binds and validates, it never reaches the
 * capture form, a submitted value is discarded, an unset one renders no row, and a server-supplied
 * one renders like any value. The production Telangana layers are exercised directly, because
 * {@code stampDutyAmount} there is the field whose blank row ({@code [ Stamp duty paid (INR) ]})
 * reached an executed deed. Pure: no Spring, no Gotenberg (the renderer is a capturing stub).
 */
class SystemSourcedFieldTest {

  private static final String RENTAL = "documents/template/sets/rental/";
  private static final String COMMERCIAL = "documents/template/sets/commercial/";
  private static final String PLACEHOLDER = "[ Stamp duty paid (INR) ]";

  private static final String DEFINITION =
      """
      meta:
        id: t
        dimensions: { state: IN, type: residential }
        version: 1
        status: draft
      fields:
        - { key: monthlyRent, label: Rent, type: money, required: true }
        - { key: dutyAmount, label: Duty paid, type: money, required: false, source: system }
      clauses:
        - { id: duty, showWhen: "dutyAmount > 0", text: "Duty paid is INR {{dutyAmount}}." }
      sections:
        - { title: Money, entries: [ monthlyRent, dutyAmount, duty ] }
      """;

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  /** Captures the HTML handed to the PDF renderer. */
  private String lastHtml;

  private final Clock clock =
      Clock.fixed(
          LocalDate.of(2026, 9, 10).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private DocumentProjectionService service(String root) {
    return new DocumentProjectionService(
        new TemplateResolver(new ClasspathLayerSource(root)),
        new TemplateCompiler(),
        (html, reference) -> {
          lastHtml = html;
          return "%PDF-".getBytes(StandardCharsets.UTF_8);
        },
        new DocumentFooterProperties("agreementmitra.com", ""),
        clock);
  }

  // --- declaration -------------------------------------------------------------

  @Test
  void theSourceBindsAndOnlyTheDeclaredFieldIsSystemSourced() {
    TemplateDefinition definition = loader.load(DEFINITION);

    assertThat(field(definition.fields(), "dutyAmount").systemSourced()).isTrue();
    assertThat(field(definition.fields(), "monthlyRent").systemSourced()).isFalse();
    assertThat(field(definition.fields(), "monthlyRent").source()).isNull();
  }

  @Test
  void aRequiredSystemSourcedFieldIsRejectedAtLoad() {
    String required =
        DEFINITION.replace("required: false, source: system", "required: true, source: system");

    assertThatThrownBy(() -> loader.load(required))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("dutyAmount");
  }

  @Test
  void anUnknownSourceTokenIsRejectedByTheSchema() {
    assertThatThrownBy(() -> loader.load(DEFINITION.replace("source: system", "source: user")))
        .isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void theSourceIsPartOfTheHashButAbsentFromUserFieldsCanonicalForm() {
    TemplateDefinition system = loader.load(DEFINITION);
    TemplateDefinition user = loader.load(DEFINITION.replace(", source: system", ""));

    assertThat(system.contentHash()).isNotEqualTo(user.contentHash());
    // A user-sourced field serializes exactly as it did before the attribute existed, so every
    // template without a system field kept its content hash (and every agreement kept its pin).
    String canonical =
        CanonicalJson.canonicalize(user.meta(), user.fields(), user.clauses(), user.sections());
    assertThat(canonical).doesNotContain("source");
  }

  // --- compile -----------------------------------------------------------------

  @Test
  void anUnsetSystemFieldRendersNoRowAndItsGatedClauseIsDropped() {
    EffectiveTemplate effective = effective(loader.load(DEFINITION));

    String html =
        new TemplateCompiler().compile(effective, Map.of("monthlyRent", new BigDecimal("25000")));

    assertThat(html).contains("Rent").doesNotContain("Duty paid").doesNotContain("[ ");
  }

  @Test
  void anUnsetSystemFieldWithAPlaceholderRendersItsRowAsAProvision() {
    EffectiveTemplate effective =
        effective(
            loader.load(
                DEFINITION.replace(
                    "source: system }", "source: system, placeholder: Provision for duty }")));

    String html =
        new TemplateCompiler().compile(effective, Map.of("monthlyRent", new BigDecimal("25000")));

    assertThat(html)
        .contains("Duty paid")
        .contains("[ Provision for duty ]")
        .doesNotContain("Duty paid is INR");
  }

  @Test
  void aSuppliedSystemValueRendersItsRowAndClause() {
    EffectiveTemplate effective = effective(loader.load(DEFINITION));

    String html =
        new TemplateCompiler()
            .compile(
                effective,
                Map.of(
                    "monthlyRent",
                    new BigDecimal("25000"),
                    "dutyAmount",
                    new BigDecimal("100.00")));

    assertThat(html).contains("Duty paid").contains("Duty paid is INR 100.00.");
  }

  @Test
  void submittedValuesForSystemFieldsAreDiscardedAndOnlyServerValuesForThemAreKept() {
    EffectiveTemplate effective = effective(loader.load(DEFINITION));
    Map<String, Object> submitted = new LinkedHashMap<>();
    submitted.put("monthlyRent", "25000");
    submitted.put("dutyAmount", "not even a number"); // discarded before validation: no error

    Map<String, Object> data =
        DocumentProjectionService.withSystemValues(
            effective, submitted, Map.of("dutyAmount", "100.00", "monthlyRent", "1"));

    assertThat(data).containsEntry("dutyAmount", "100.00").containsEntry("monthlyRent", "25000");
    assertThat(DocumentProjectionService.withSystemValues(effective, submitted, Map.of()))
        .doesNotContainKey("dutyAmount");
  }

  // --- the production Telangana layers ---------------------------------------------

  @Test
  void theTelanganaStampDutyAmountIsSystemSourcedInBothProductLines() {
    for (String[] set : new String[][] {{RENTAL, "residential"}, {COMMERCIAL, "commercial"}}) {
      EffectiveTemplate tg = resolve(set[0], set[1]);
      assertThat(field(tg.template().fields(), "stampDutyAmount").systemSourced()).isTrue();

      FormSchema form = new FormProjector().project(tg);
      assertThat(form.sections().stream().flatMap(s -> s.fields().stream()).map(FormField::key))
          .contains("registrationChargesBorneBy")
          .doesNotContain("stampDutyAmount");
    }
  }

  @Test
  void aTelanganaDraftShowsTheStampDutyProvisionNeverASubmittedAmount() {
    DocumentProjectionService service = service(RENTAL);
    Map<String, Object> data = aggregateBackedData();
    data.put("stampDutyAmount", "98765"); // what a client might still send

    String preview = service.previewHtml(tgRequest(data));
    service.generate(tgRequest(data));

    for (String html : List.of(preview, lastHtml)) {
      assertThat(html)
          .contains("Statutory (Telangana)")
          // The draft the parties review before paying keeps a visible provision for the duty.
          .contains("Stamp duty paid (INR)")
          .contains("[ Provision for stamp duty ]")
          .doesNotContain(PLACEHOLDER)
          .doesNotContain("The stamp duty paid on this Agreement is INR")
          .doesNotContain("98765");
    }
  }

  @Test
  void aTelanganaDeedGeneratedWithTheCertificateDutyStatesIt() {
    DocumentProjectionResult result =
        service(RENTAL)
            .generate(
                tgRequest(aggregateBackedData()),
                Map.of("stampDutyAmount", new BigDecimal("100.00")));

    assertThat(lastHtml)
        .contains("Stamp duty paid (INR)")
        .contains("The stamp duty paid on this Agreement is INR 100.00.")
        .doesNotContain("Provision for stamp duty")
        .doesNotContain(PLACEHOLDER);
    // No agreementDate was captured, so the deed printed the render date -- and says so, so that a
    // later re-render can pass the same date back.
    assertThat(result.executionDate()).isEqualTo("2026-09-10");
  }

  @Test
  void everyClauseSlottingASystemFieldIsGatedOnIt() {
    for (String[] set :
        new String[][] {
          {RENTAL, "IN", "residential"},
          {RENTAL, "TG", "residential"},
          {COMMERCIAL, "IN", "commercial"},
          {COMMERCIAL, "TG", "commercial"}
        }) {
      EffectiveTemplate eff =
          new TemplateResolver(new ClasspathLayerSource(set[0]))
              .resolve(new Dimensions(set[1], set[2]));
      Set<String> systemKeys =
          eff.template().fields().stream()
              .filter(Field::systemSourced)
              .map(Field::key)
              .collect(java.util.stream.Collectors.toSet());
      for (Clause clause : eff.template().clauses()) {
        if (clause instanceof Clause.Inline inline) {
          for (String slot : inline.slots()) {
            if (systemKeys.contains(slot)) {
              assertThat(inline.showWhen())
                  .as("clause %s in %s/%s slots system field %s", inline.id(), set[1], set[2], slot)
                  .isNotNull()
                  .contains(slot);
            }
          }
        }
      }
    }
  }

  // --- helpers -------------------------------------------------------------------

  private static EffectiveTemplate resolve(String root, String type) {
    return new TemplateResolver(new ClasspathLayerSource(root)).resolve(new Dimensions("TG", type));
  }

  private static DocumentProjectionRequest tgRequest(Map<String, Object> data) {
    return new DocumentProjectionRequest(
        new DocumentDimensions("TG", "residential"), data, List.of(), "AMPSFTXU5KV");
  }

  private static Map<String, Object> aggregateBackedData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Owner");
    data.put("tenantName", "Bhaskar Tenant");
    data.put("propertyAddress", "Plot 7, Jubilee Hills, Hyderabad");
    data.put("monthlyRent", new BigDecimal("25000.00"));
    data.put("securityDeposit", new BigDecimal("100000.00"));
    data.put("durationMonths", 11);
    data.put("startDate", "2026-08-01");
    data.put("endDate", "2027-06-30");
    return data;
  }

  private static EffectiveTemplate effective(TemplateDefinition definition) {
    LayerSource.LayerSet set =
        new LayerSource.LayerSet(
            new LayerSource.LayerSet.Base(
                new LayerRef(
                    LayerKind.BASE,
                    definition.meta().dimensions(),
                    definition.meta().version(),
                    "base"),
                definition),
            List.of());
    return new TemplateResolver((s, t) -> set).resolve(new Dimensions("IN", "residential"));
  }

  private static Field field(List<Field> fields, String key) {
    return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
  }
}
