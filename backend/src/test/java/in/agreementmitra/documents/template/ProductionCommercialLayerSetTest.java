package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Resolves + compiles the production commercial layer set ({@code
 * documents/template/sets/commercial/}) for both dimensions the catalog seeds: national {@code (IN,
 * commercial)} and {@code (TG, commercial)}. Pure -- real resource I/O but no Spring context, no
 * Testcontainers, no Docker -- so it runs in {@code ./gradlew test} regardless of the local Docker
 * daemon.
 *
 * <p>Guards the commercial structure: (1) the set composes + re-validates cleanly for IN and TG;
 * (2) the document is correctly headed "Commercial Lease Agreement" (NOT the residential header)
 * with split Lessor/Lessee party cards; (3) the commercial covenants render (permitted business use
 * with an explicit no-residential covenant, CAM, GST); (4) the Telangana overlay applies last
 * (statutory section, TG law/stamp clauses in, the generic national stamp clause out, Hyderabad
 * jurisdiction default); and (5) the PARITY CONTRACT holds -- a generate projection fed ONLY the
 * eight aggregate-backed field keys validates + compiles without a missing-required error, with the
 * same keys the residential base uses (relabelled for commercial).
 */
class ProductionCommercialLayerSetTest {

  private static final String ROOT = "documents/template/sets/commercial/";

  private static final List<String> BASE_SECTIONS =
      List.of(
          "Lessor",
          "Lessee",
          "Schedule of Premises",
          "Term",
          "Financial",
          "Charges & Utilities",
          "Occupancy & Use",
          "Dispute Resolution",
          "Now This Agreement Witnesseth",
          "Annexure",
          "In Witness Whereof",
          "Witnesses");

  private static final List<String> TG_SECTIONS =
      List.of(
          "Lessor",
          "Lessee",
          "Schedule of Premises",
          "Term",
          "Financial",
          "Charges & Utilities",
          "Occupancy & Use",
          "Dispute Resolution",
          "Now This Agreement Witnesseth",
          "Annexure",
          "Statutory (Telangana)",
          "In Witness Whereof",
          "Witnesses");

  private static final Set<String> MANDATORY_SECTIONS =
      Set.of(
          "Lessor",
          "Lessee",
          "Schedule of Premises",
          "Term",
          "Financial",
          "Now This Agreement Witnesseth",
          "In Witness Whereof");

  // Exactly the keys AgreementDocumentMapper.toTemplateData emits from the persisted aggregate --
  // the SAME keys the residential base uses, relabelled for commercial. This is the parity
  // contract.
  private static Map<String, Object> aggregateBackedData() {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("ownerName", "Acme Estates Pvt Ltd");
    data.put("tenantName", "Bright Software LLP");
    data.put("propertyAddress", "Unit 4, HITEC City, Hyderabad");
    data.put("monthlyRent", new BigDecimal("150000.00"));
    data.put("securityDeposit", new BigDecimal("900000.00"));
    data.put("durationMonths", 36);
    data.put("startDate", "2026-09-01");
    data.put("endDate", "2029-08-31");
    return data;
  }

  @Test
  void nationalResolvesWithTheAuthoredSectionsAndCommercialUsePinned() {
    EffectiveTemplate eff = resolve("IN", "commercial");

    assertThat(sectionTitles(eff)).isEqualTo(BASE_SECTIONS);
    // The parity contract: the same eight aggregate-backed keys the mapper supplies are declared.
    assertThat(fieldKeys(eff))
        .contains(
            "ownerName",
            "tenantName",
            "propertyAddress",
            "monthlyRent",
            "securityDeposit",
            "durationMonths",
            "startDate",
            "endDate");
    // Residential-only fields do NOT carry over.
    assertThat(fieldKeys(eff))
        .doesNotContain("bhkConfiguration", "furnishingStatus", "petsAllowed", "maxOccupants");

    // type-commercial override: permitted use is required and defaulted to commercial.
    Field permittedUse = field(eff, "permittedUse");
    assertThat(permittedUse.required()).isTrue();
    assertThat(permittedUse.defaultValue()).isEqualTo("commercial");
  }

  @Test
  void everyRequiredFieldIsAggregateBackedOrDefaultedSoGenerateParityHolds() {
    // The parity contract: at generate the aggregate supplies only these eight keys; every OTHER
    // required field must carry a default (e.g. permittedUse, pinned required by type-commercial
    // but
    // defaulted to commercial), so a generated draft never trips a missing-required error.
    Set<String> aggregateKeys =
        Set.of(
            "ownerName",
            "tenantName",
            "propertyAddress",
            "monthlyRent",
            "securityDeposit",
            "durationMonths",
            "startDate",
            "endDate");
    EffectiveTemplate eff = resolve("IN", "commercial");
    for (Field f : eff.template().fields()) {
      if (f.required()) {
        assertThat(aggregateKeys.contains(f.key()) || f.defaultValue() != null)
            .as("required field '%s' must be aggregate-backed or defaulted", f.key())
            .isTrue();
      }
    }
  }

  @Test
  void nationalDeclaresTheCommercialHeaderNotTheResidentialOne() {
    EffectiveTemplate eff = resolve("IN", "commercial");

    DocumentMeta document = eff.template().meta().document();
    assertThat(document).isNotNull();
    assertThat(document.title()).isEqualTo("Commercial Lease Agreement");
    assertThat(document.subtitle()).isNotEqualTo("Residential Tenancy (Leave & Licence)");

    // The recital renders the Lessor/Lessee parties.
    String html = new TemplateCompiler().compile(eff, aggregateBackedData());
    assertThat(html).contains("Acme Estates Pvt Ltd").contains("Bright Software LLP");
  }

  @Test
  void mandatoryAndOptionalFlagsAndRenderKindsAreDeclaredPerSection() {
    EffectiveTemplate eff = resolve("IN", "commercial");

    for (Section section : eff.template().sections()) {
      boolean shouldBeMandatory = MANDATORY_SECTIONS.contains(section.title());
      assertThat(section.optional())
          .as("section '%s' optional flag", section.title())
          .isEqualTo(!shouldBeMandatory);
    }

    assertThat(section(eff, "Lessor").render()).isEqualTo(RenderKind.PARTIES);
    assertThat(section(eff, "Lessee").render()).isEqualTo(RenderKind.PARTIES);
    assertThat(section(eff, "Schedule of Premises").render()).isEqualTo(RenderKind.KEYVALUE);
    assertThat(section(eff, "Now This Agreement Witnesseth").render())
        .isEqualTo(RenderKind.CLAUSES);
    assertThat(section(eff, "In Witness Whereof").render()).isEqualTo(RenderKind.SIGNATURES);

    // The witnesseth section carries clause ids only (zero field entries) -- document structure.
    List<String> fieldKeys = fieldKeys(eff);
    assertThat(section(eff, "Now This Agreement Witnesseth").entries())
        .noneMatch(fieldKeys::contains);
  }

  @Test
  void telanganaAppliesTheStatutoryOverlayLastOverTheCommercialStructure() {
    EffectiveTemplate eff = resolve("TG", "commercial");

    List<String> titles = sectionTitles(eff);
    assertThat(titles).hasSize(13).isEqualTo(TG_SECTIONS);
    assertThat(titles.indexOf("Statutory (Telangana)"))
        .isEqualTo(titles.indexOf("In Witness Whereof") - 1);

    assertThat(fieldKeys(eff)).contains("stampDutyAmount", "registrationChargesBorneBy");
    assertThat(clauseIds(eff))
        .contains("tgGoverningLaw", "tgStampRegistration")
        .doesNotContain("stampRegistrationClause"); // generic national clause dropped for TG

    assertThat(field(eff, "jurisdictionCity").defaultValue()).isEqualTo("Hyderabad");
    assertThat(section(eff, "Now This Agreement Witnesseth").entries())
        .doesNotContain("stampRegistrationClause");
  }

  @Test
  void generateParityHoldsWithOnlyTheAggregateBackedDataForBothDimensions() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "commercial");

      Map<String, Object> coerced =
          SubmittedDataValidator.validateAndCoerce(
              eff, aggregateBackedData(), ProjectionMode.GENERATE);

      // Defaulted fields the aggregate never supplied are filled from the template.
      assertThat(coerced).containsEntry("permittedUse", "commercial");

      assertThatCode(() -> new TemplateCompiler().compile(eff, coerced)).doesNotThrowAnyException();
    }
  }

  @Test
  void commercialCovenantsRenderInAGeneratedDraft() {
    EffectiveTemplate eff = resolve("TG", "commercial");
    Map<String, Object> coerced =
        SubmittedDataValidator.validateAndCoerce(
            eff, aggregateBackedData(), ProjectionMode.GENERATE);

    // Mandatory covenants (witnesseth + Financial) render by default.
    String html = new TemplateCompiler().compile(eff, coerced);
    assertThat(html)
        .contains("shall NOT be used for residential purposes") // explicit no-residential covenant
        .contains("used solely for Commercial purposes") // humanised permittedUse enum
        .contains("Goods and Services Tax") // GST clause lives in the mandatory Financial section
        .contains("In Witness Whereof")
        .contains("esign:owner")
        .contains("esign:tenant");

    // Opt-in Charges & Utilities carries the CAM + repairs split covenants.
    String withCharges =
        new TemplateCompiler().compile(eff, coerced, null, Set.of("Charges & Utilities"));
    assertThat(withCharges).contains("Common-area maintenance").contains("Structural repairs");

    // Opt-in statutory overlay renders alongside the always-on signature block.
    String withStatutory =
        new TemplateCompiler().compile(eff, coerced, null, Set.of("Statutory (Telangana)"));
    assertThat(withStatutory)
        .contains("Statutory (Telangana)")
        .contains("Registration Act, 1908")
        .contains("In Witness Whereof");
  }

  @Test
  void compilesToTheArtifactLayoutInvariantsForBothDimensions() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "commercial");
      Map<String, Object> coerced =
          SubmittedDataValidator.validateAndCoerce(
              eff, aggregateBackedData(), ProjectionMode.GENERATE);
      String html =
          new TemplateCompiler(DocumentFonts.faceCss()).compile(eff, coerced, "1 September 2026");

      assertThat(html)
          .contains("<div class=\"doc-header\">")
          .contains("Commercial Lease Agreement");
      assertThat(html).contains("<h2>Lessor</h2>").contains("<h2>Lessee</h2>");
      assertThat(html).contains("<div class=\"party-card\">");
      assertThat(html).contains("<table class=\"kv\">");
      assertThat(html).contains("<ol class=\"clauses\">");
      assertThat(html)
          .contains("In Witness Whereof")
          .contains("esign:owner")
          .contains("esign:tenant");
      // Self-contained: no external URL of any scheme.
      assertThat(html).doesNotContain("http://").doesNotContain("https://");
    }
  }

  @Test
  void theFieldlessWitnessethSectionIsOmittedFromTheCaptureForm() {
    for (String state : List.of("IN", "TG")) {
      EffectiveTemplate eff = resolve(state, "commercial");
      FormSchema schema = new FormProjector().project(eff);
      List<String> formTitles = schema.sections().stream().map(FormSection::title).toList();

      assertThat(formTitles).doesNotContain("Now This Agreement Witnesseth");
      assertThat(formTitles)
          .contains("Lessor", "Lessee", "Schedule of Premises", "Term", "Financial");
      assertThat(formSection(schema, "Lessor").renderKind()).isEqualTo("parties");
      assertThat(formSection(schema, "Charges & Utilities").optional()).isTrue();
    }
  }

  // --- helpers ---------------------------------------------------------------

  private static EffectiveTemplate resolve(String state, String type) {
    TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
    LayerPatchLoader patchLoader = new LayerPatchLoader();

    TemplateDefinition base = definitionLoader.loadResource(ROOT + "base.yaml");
    LayerSource.LayerSet.Base baseLayer =
        new LayerSource.LayerSet.Base(
            new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), "base"),
            base);

    List<LayerSource.LayerSet.Patch> patches = new ArrayList<>();
    addIfPresent(patches, patchLoader, LayerKind.TYPE, ROOT + "type-" + type + ".patch.yaml");
    addIfPresent(patches, patchLoader, LayerKind.STATE, ROOT + "state-" + state + ".patch.yaml");
    addIfPresent(
        patches,
        patchLoader,
        LayerKind.STATE_TYPE,
        ROOT + "state_type-" + state + "-" + type + ".patch.yaml");

    LayerSource.LayerSet set = new LayerSource.LayerSet(baseLayer, patches);
    LayerSource source = (s, t) -> set;
    return new TemplateResolver(source).resolve(new Dimensions(state, type));
  }

  private static void addIfPresent(
      List<LayerSource.LayerSet.Patch> patches,
      LayerPatchLoader loader,
      LayerKind expected,
      String path) {
    if (ProductionCommercialLayerSetTest.class.getClassLoader().getResource(path) == null) {
      return;
    }
    LayerPatch patch = loader.loadResource(path);
    LayerRef ref = new LayerRef(expected, patch.meta().dimensions(), patch.meta().version(), path);
    patches.add(new LayerSource.LayerSet.Patch(ref, patch));
  }

  private static List<String> sectionTitles(EffectiveTemplate eff) {
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

  private static FormSection formSection(FormSchema schema, String title) {
    return schema.sections().stream()
        .filter(s -> s.title().equals(title))
        .findFirst()
        .orElseThrow();
  }
}
