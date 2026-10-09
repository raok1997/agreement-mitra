package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import in.agreementmitra.support.TemplateParity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Resolves + compiles the Karnataka layers over BOTH production layer sets -- {@code (KA,
 * residential)} over {@code sets/rental/} and {@code (KA, commercial)} over {@code
 * sets/commercial/} (change ka-rental-and-commercial-templates). Pure: real resource I/O, no Spring
 * context, no Testcontainers, so it runs under {@code ./gradlew test} regardless of the local
 * Docker daemon.
 *
 * <p>Two properties carry most of the weight here, because both are ways this change could be
 * quietly wrong rather than loudly broken:
 *
 * <ul>
 *   <li><b>No deed without a stamp clause.</b> The Karnataka {@code state_type} layers remove the
 *       national {@code stampRegistrationClause}, so the Karnataka statutory section MUST be
 *       mandatory or a default Karnataka deed carries no stamp or registration clause at all. That
 *       is the defect Telangana shipped for two months and the Telangana commercial set still
 *       carries; {@link #exactlyOneStampAndRegistrationClauseRendersByDefault(String, String)} is
 *       what stops it recurring here.
 *   <li><b>The right set's section titles.</b> The two sets name their sections differently (Owner
 *       / Tenant / Schedule of Property against Lessor / Lessee / Schedule of Premises). A
 *       Telangana ordering copied into the wrong Karnataka layer would drop every section from the
 *       reorder, which the ordering assertions below detect.
 * </ul>
 */
class ProductionKarnatakaLayerSetsTest {

  private static final String RENTAL_ROOT = "documents/template/sets/rental/";
  private static final String COMMERCIAL_ROOT = "documents/template/sets/commercial/";

  private static final String STATUTORY = "Statutory (Karnataka)";

  private static String rootFor(String type) {
    return "residential".equals(type) ? RENTAL_ROOT : COMMERCIAL_ROOT;
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theKarnatakaLayersComposeAndRevalidate(String type) {
    assertThatCode(() -> resolve(type)).doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theStatutorySectionIsMandatoryAndSitsBeforeTheSignatureBlock(String type) {
    EffectiveTemplate eff = resolve(type);

    assertThat(section(eff, STATUTORY).optional())
        .as("a Karnataka deed must never render without its stamp/registration clause")
        .isFalse();
    List<String> titles = titles(eff);
    assertThat(titles).contains(STATUTORY);
    assertThat(titles.indexOf(STATUTORY)).isEqualTo(titles.indexOf("In Witness Whereof") - 1);
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({
    "residential, Owner, Tenant, Schedule of Property",
    "commercial, Lessor, Lessee, Schedule of Premises"
  })
  void eachSetKeepsItsOwnSectionTitlesThroughTheReorder(
      String type, String firstParty, String secondParty, String schedule) {
    // If a Karnataka reorder named the sibling set's titles, the reorder would not match these
    // sections and the document would come out in base order -- or not at all.
    assertThat(titles(resolve(type)))
        .startsWith(firstParty, secondParty, schedule, "Term", "Financial");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theKarnatakaOverlayReplacesTheNationalStampClause(String type) {
    EffectiveTemplate eff = resolve(type);

    assertThat(fieldKeys(eff)).contains("stampDutyAmount", "registrationChargesBorneBy");
    assertThat(clauseIds(eff))
        .contains("kaGoverningLaw", "kaStampRegistration", "kaStampAmount")
        .doesNotContain("stampRegistrationClause");
    // Nothing dangles: the witnesseth list no longer names the removed national clause.
    assertThat(section(eff, "Now This Agreement Witnesseth").entries())
        .doesNotContain("stampRegistrationClause");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void exactlyOneStampAndRegistrationClauseRendersByDefault(String type) {
    // No activeSections: the default deed the parties are emailed before paying.
    String html = compileWithNoOptionalSections(type);

    assertThat(html).contains(STATUTORY).contains("Karnataka Stamp Act, 1957");
    assertThat(html.split("registered before the jurisdictional Sub-Registrar", -1))
        .as("exactly one stamp/registration clause on a default Karnataka deed")
        .hasSize(2);
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theDeedStatesNoRegistrationThreshold(String type) {
    // The threshold lives only in rules/stamp-duty/KA/*.yaml (requiredWhenTermMonthsOver), which
    // drives the quote's registration notice. A deed that stated its own threshold could contradict
    // the quote -- Telangana's did -- so the deed defers to the law instead.
    assertThat(compileWithNoOptionalSections(type))
        .contains("where the law requires it, registered")
        .doesNotContain("twelve (12) months, compulsorily")
        .doesNotContain("eleven (11) months");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theAlwaysOnJurisdictionCovenantNamesACourt(String type) {
    // The dispute covenant sits in the MANDATORY witnesseth list, so it renders on every deed while
    // jurisdictionCity lives in the OPTIONAL Dispute Resolution section. Without the Karnataka
    // default this would read "the courts at [ Jurisdiction city ]".
    EffectiveTemplate eff = resolve(type);

    assertThat(field(eff, "jurisdictionCity").defaultValue()).isEqualTo("Bengaluru");
    assertThat(compileWithNoOptionalSections(type))
        .contains("courts at Bengaluru")
        .doesNotContain("[ Jurisdiction city ]");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theStampDutyAmountIsNeverAskedForAndShowsAProvisionUntilStamped(String type) {
    EffectiveTemplate eff = resolve(type);

    FormSchema schema = new FormProjector().project(eff);
    assertThat(
            schema.sections().stream()
                .map(FormSection::fields)
                .flatMap(List::stream)
                .map(f -> f.key()))
        .as("the customer cannot know the duty; it comes from the certificate at stamp intake")
        .doesNotContain("stampDutyAmount");

    // Before stamping the row shows the provision, and the amount clause stays out entirely.
    assertThat(compileWithNoOptionalSections(type))
        .contains("Provision for stamp duty")
        .doesNotContain("The stamp duty paid on this Agreement is INR");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theParityContractHoldsForKarnataka(String type) {
    // generate-as-draft maps the aggregate-backed keys plus the user-answered fields; every other
    // field must be optional or defaulted, or a Karnataka draft would fail required-validation
    // where a preview succeeded.
    EffectiveTemplate eff = resolve(type);
    assertThat(aggregateBackedData().keySet()).isEqualTo(TemplateParity.AGGREGATE_KEYS);
    assertThat(SublettingCovenants.parityViolations(eff)).isEmpty();

    assertThatCode(
            () ->
                new TemplateCompiler()
                    .compile(
                        eff,
                        SubmittedDataValidator.validateAndCoerce(
                            eff,
                            TemplateParity.withUserAnswers(aggregateBackedData()),
                            ProjectionMode.GENERATE)))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential", "commercial"})
  void theSignatureBlockCarriesBothEsignAnchors(String type) {
    assertThat(compileWithNoOptionalSections(type))
        .contains("esign:owner")
        .contains("esign:tenant");
  }

  @ParameterizedTest(name = "(KA, {0})")
  @CsvSource({"residential,Tenant,Owner", "commercial,Lessee,Lessor"})
  void eachSublettingOptionRendersItsOwnCovenant(String type, String party, String counterparty) {
    EffectiveTemplate eff = resolve(type);
    SublettingCovenants.assertRequiredTermFieldWithNoDefault(
        eff, "residential".equals(type) ? "noticePeriodMonths" : "fitOutMonths");
    SublettingCovenants.assertTheWitnessethListsAllThreeCovenants(eff);
    assertThat(clauseIds(eff))
        .containsAll(SublettingCovenants.CLAUSE_IDS)
        .doesNotContain("noSublettingClause");
    SublettingCovenants.assertEachOptionRendersItsOwnCovenant(
        eff, aggregateBackedData(), party, counterparty);
  }

  // --- helpers -------------------------------------------------------------------------------

  private static String compileWithNoOptionalSections(String type) {
    EffectiveTemplate eff = resolve(type);
    return new TemplateCompiler()
        .compile(
            eff,
            SubmittedDataValidator.validateAndCoerce(
                eff,
                TemplateParity.withUserAnswers(aggregateBackedData()),
                ProjectionMode.GENERATE),
            null,
            Set.of());
  }

  private static Map<String, Object> aggregateBackedData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Asha Owner");
    data.put("ownerFatherName", "Ravi Owner");
    data.put("ownerAddress", "1 First Street");
    data.put("tenantName", "Bhaskar Tenant");
    data.put("tenantFatherName", "Kiran Tenant");
    data.put("tenantAddress", "2 Second Street");
    data.put("propertyAddress", "Flat 4B, Indiranagar, Bengaluru");
    data.put("monthlyRent", new BigDecimal("25000.00"));
    data.put("securityDeposit", new BigDecimal("100000.00"));
    data.put("durationMonths", 11);
    data.put("startDate", "2026-08-01");
    data.put("endDate", "2027-06-30");
    return data;
  }

  private static EffectiveTemplate resolve(String type) {
    String root = rootFor(type);
    TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
    LayerPatchLoader patchLoader = new LayerPatchLoader();

    TemplateDefinition base = definitionLoader.loadResource(root + "base.yaml");
    LayerSource.LayerSet.Base baseLayer =
        new LayerSource.LayerSet.Base(
            new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), "base"),
            base);

    List<LayerSource.LayerSet.Patch> patches = new ArrayList<>();
    addIfPresent(patches, patchLoader, LayerKind.TYPE, root + "type-" + type + ".patch.yaml");
    addIfPresent(patches, patchLoader, LayerKind.STATE, root + "state-KA.patch.yaml");
    addIfPresent(
        patches, patchLoader, LayerKind.STATE_TYPE, root + "state_type-KA-" + type + ".patch.yaml");

    LayerSource.LayerSet set = new LayerSource.LayerSet(baseLayer, patches);
    LayerSource source = (s, t) -> set;
    return new TemplateResolver(source).resolve(new Dimensions("KA", type));
  }

  private static void addIfPresent(
      List<LayerSource.LayerSet.Patch> patches,
      LayerPatchLoader loader,
      LayerKind expected,
      String path) {
    if (ProductionKarnatakaLayerSetsTest.class.getClassLoader().getResource(path) == null) {
      return;
    }
    LayerPatch patch = loader.loadResource(path);
    LayerRef ref = new LayerRef(expected, patch.meta().dimensions(), patch.meta().version(), path);
    patches.add(new LayerSource.LayerSet.Patch(ref, patch));
  }

  private static List<String> titles(EffectiveTemplate eff) {
    return eff.template().sections().stream().map(Section::title).toList();
  }

  private static List<String> fieldKeys(EffectiveTemplate eff) {
    return eff.template().fields().stream().map(Field::key).toList();
  }

  private static List<String> clauseIds(EffectiveTemplate eff) {
    return eff.template().clauses().stream()
        .filter(Clause.Inline.class::isInstance)
        .map(c -> ((Clause.Inline) c).id())
        .toList();
  }

  private static Field field(EffectiveTemplate eff, String key) {
    return eff.template().fields().stream()
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static Section section(EffectiveTemplate eff, String title) {
    return eff.template().sections().stream()
        .filter(s -> s.title().equals(title))
        .findFirst()
        .orElseThrow();
  }
}
