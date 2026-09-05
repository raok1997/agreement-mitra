package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.documents.api.FormField;
import in.agreementmitra.documents.api.FormSchema;
import in.agreementmitra.documents.api.FormSection;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Unit tests for {@link FormProjector}: the pure, data-independent projection of an effective
 * template into a {@link FormSchema}. No Spring context, no I/O -- fixtures are built directly from
 * the (package-private) definition records. Covers structure, the closed widget mapping, validation
 * metadata, determinism/data-independence, and immutability.
 */
class FormProjectorTest {

  private final FormProjector projector = new FormProjector();

  // --- 5.1 structure --------------------------------------------------------

  @Test
  void projectsSectionsAndFieldsInAuthoredOrderCarryingIdentity() {
    FormSchema schema = projector.project(referenceTemplate());

    assertThat(schema.dimensions()).isEqualTo(new FormSchema.Dimensions("TG", "residential"));
    assertThat(schema.templateId()).isEqualTo("rental-base");
    assertThat(schema.version()).isEqualTo(3);
    assertThat(schema.contentHash()).isEqualTo("hash-abc");

    assertThat(schema.sections())
        .extracting(FormSection::title)
        .containsExactly("Parties", "Terms");
    // Section order and within-section field order are both preserved.
    assertThat(schema.sections().get(0).fields())
        .extracting(FormField::key)
        .containsExactly("ownerName", "notes");
    assertThat(schema.sections().get(1).fields())
        .extracting(FormField::key)
        .containsExactly("durationMonths", "rent", "startDate", "furnished", "regResp");
  }

  @Test
  void excludesClauseIdEntriesFromFields() {
    FormSchema schema = projector.project(referenceTemplate());

    // "partiesClause" is a clause id placed in the Parties section; it is document content, not a
    // form input, so it must not appear as a field.
    assertThat(schema.sections().get(0).fields())
        .extracting(FormField::key)
        .doesNotContain("partiesClause");
    assertThat(allFields(schema)).extracting(FormField::key).doesNotContain("partiesClause");
  }

  // --- 5.2 widget mapping ---------------------------------------------------

  @ParameterizedTest
  @EnumSource(FieldType.class)
  void everyFieldTypeMapsToANonBlankWidget(FieldType type) {
    // Projecting a one-field template of each type must yield a widget -- a loud guard that a new
    // FieldType added without a widget case is caught (the switch is exhaustive; this asserts it).
    FormField field = onlyField(projector.project(singleFieldTemplate(type)));
    assertThat(field.widget()).isNotBlank();
    assertThat(field.type()).isEqualTo(type.name().toLowerCase(java.util.Locale.ROOT));
  }

  @Test
  void mapsEachFieldTypeToItsExpectedWidget() {
    Map<FieldType, String> expected = new EnumMap<>(FieldType.class);
    expected.put(FieldType.TEXT, "text");
    expected.put(FieldType.LONGTEXT, "textarea");
    expected.put(FieldType.INT, "number");
    expected.put(FieldType.MONEY, "money");
    expected.put(FieldType.DATE, "date");
    expected.put(FieldType.BOOL, "checkbox");
    expected.put(FieldType.ENUM, "select");

    // Fails loudly if a new FieldType is added without extending the expectation here.
    assertThat(expected.keySet()).containsExactlyInAnyOrder(FieldType.values());
    expected.forEach(
        (type, widget) ->
            assertThat(onlyField(projector.project(singleFieldTemplate(type))).widget())
                .as("widget for %s", type)
                .isEqualTo(widget));
  }

  // --- 5.3 validation metadata ----------------------------------------------

  @Test
  void carriesValidationMetadataVerbatim() {
    Map<String, FormField> byKey = fieldsByKey(projector.project(referenceTemplate()));

    FormField owner = byKey.get("ownerName");
    assertThat(owner.required()).isTrue();
    assertThat(owner.validation().minLength()).isEqualTo(2);
    assertThat(owner.validation().maxLength()).isEqualTo(120);
    assertThat(owner.validation().pattern()).isEqualTo("[A-Za-z ]+");

    FormField duration = byKey.get("durationMonths");
    assertThat(duration.validation().min()).isEqualTo(1L);
    assertThat(duration.validation().max()).isEqualTo(60L);

    FormField regResp = byKey.get("regResp");
    assertThat(regResp.options())
        .extracting(FormField.Option::value)
        .containsExactly("owner", "tenant");
    assertThat(regResp.options())
        .extracting(FormField.Option::label)
        .containsExactly("Owner", "Tenant");
    assertThat(regResp.required()).isFalse();

    FormField furnished = byKey.get("furnished");
    assertThat(furnished.defaultValue()).isEqualTo(Boolean.FALSE);
    // Non-enum fields carry no options.
    assertThat(furnished.options()).isNull();
  }

  // --- section semantics: optional + renderKind + field-less omission -------

  @Test
  void surfacesOptionalFlagAndRenderKindOnAnOptionalFieldBearingSection() {
    // 4.1: an optional (optional = true) field-bearing section with a declared renderKind projects
    // to a present FormSection carrying optional == true and that exact renderKind, fields in
    // order.
    FormSchema schema = projector.project(mixedTemplate());

    FormSection addOns = sectionByTitle(schema, "Add-ons");
    assertThat(addOns.optional()).isTrue();
    assertThat(addOns.renderKind()).isEqualTo("annexure");
    assertThat(addOns.fields()).extracting(FormField::key).containsExactly("notes");
  }

  @Test
  void marksAFieldBearingMandatorySectionMandatoryWithItsRenderKind() {
    // 4.2: a field-bearing section declared optional = false projects to a present FormSection with
    // optional == false and its declared renderKind.
    FormSchema schema = projector.project(mixedTemplate());

    FormSection parties = sectionByTitle(schema, "Parties");
    assertThat(parties.optional()).isFalse();
    assertThat(parties.renderKind()).isEqualTo("parties");
    assertThat(parties.fields()).extracting(FormField::key).containsExactly("ownerName");
  }

  @Test
  void omitsAFieldLessDocumentOnlySection() {
    // 4.3: a section whose entries are all clause ids (zero field keys) is omitted, while an
    // adjacent field-bearing section stays present in authored order.
    FormSchema schema = projector.project(mixedTemplate());

    assertThat(schema.sections())
        .extracting(FormSection::title)
        .containsExactly("Parties", "Add-ons") // "Witnesseth" (clause-only) is dropped
        .doesNotContain("Witnesseth");
  }

  @Test
  void keepsAFieldLessOptionalSectionAsAnAddableToggle() {
    // A field-less MANDATORY section is document-only and omitted (see the test above). A
    // field-less
    // OPTIONAL section is RETAINED so the Add-optional catalog can surface it -- it toggles into
    // the
    // document with nothing to fill (e.g. an opt-in "In Witness Whereof" signature block).
    List<Field> fields =
        List.of(new Field("ownerName", "Owner", FieldType.TEXT, true, null, null, null, null));
    List<Section> sections =
        List.of(
            new Section("Parties", List.of("ownerName"), false, RenderKind.PARTIES),
            new Section("In Witness Whereof", List.of(), true, RenderKind.SIGNATURES));

    FormSchema schema = projector.project(effectiveOf(fields, List.of(), sections));

    assertThat(schema.sections()).extracting(FormSection::title).contains("In Witness Whereof");
    FormSection signature = sectionByTitle(schema, "In Witness Whereof");
    assertThat(signature.optional()).isTrue();
    assertThat(signature.renderKind()).isEqualTo("signatures");
    assertThat(signature.fields()).isEmpty();
  }

  // --- 5.4 determinism + data-independence ----------------------------------

  @Test
  void isDeterministicAcrossRepeatedProjection() {
    EffectiveTemplate template = referenceTemplate();
    assertThat(projector.project(template)).isEqualTo(projector.project(template));
  }

  @Test
  void isDeterministicWithOptionalMandatoryAndFieldLessSections() {
    // 4.4: two projections of the same effective template (mixing an optional field-bearing
    // section, a mandatory field-bearing section, and a field-less section) are equal -- including
    // the new fields and the omitted section -- with no clock/IO/randomness/user-data dependence.
    EffectiveTemplate template = mixedTemplate();
    assertThat(projector.project(template)).isEqualTo(projector.project(template));
  }

  @Test
  void carriesNoShowWhenOntoFieldsAndMarksNoFieldHidden() {
    // A showWhen-bearing clause is carried by the definition but is document content; the projector
    // evaluates nothing and never sets a field-level showWhen (the reserved slot stays null).
    assertThat(allFields(projector.project(referenceTemplate())))
        .allSatisfy(field -> assertThat(field.showWhen()).isNull());
  }

  // --- enum option labels ---------------------------------------------------

  @Test
  void humanizeOptionYieldsTheReviewedLabels() {
    // Plain Title-Case + _-to-space.
    assertThat(OptionLabels.humanize("apartment")).isEqualTo("Apartment");
    assertThat(OptionLabels.humanize("independent_house")).isEqualTo("Independent House");
    assertThat(OptionLabels.humanize("bank_transfer")).isEqualTo("Bank Transfer");
    assertThat(OptionLabels.humanize("two_wheeler")).isEqualTo("Two Wheeler");
    assertThat(OptionLabels.humanize("shared")).isEqualTo("Shared");
    // Acronyms kept upper-case.
    assertThat(OptionLabels.humanize("upi")).isEqualTo("UPI");
    assertThat(OptionLabels.humanize("pg_room")).isEqualTo("PG Room");
    // Already-capitalised tokens preserved verbatim.
    assertThat(OptionLabels.humanize("1BHK")).isEqualTo("1BHK");
  }

  // --- 5.5 no user data / immutability --------------------------------------

  @Test
  void projectedSchemaIsImmutable() {
    FormSchema schema = projector.project(referenceTemplate());

    assertThatThrownBy(() -> schema.sections().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> schema.sections().get(0).fields().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    FormField enumField = fieldsByKey(schema).get("regResp");
    assertThatThrownBy(() -> enumField.options().add(new FormField.Option("landlord", "Landlord")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // --- fixtures -------------------------------------------------------------

  private static EffectiveTemplate referenceTemplate() {
    List<Field> fields =
        List.of(
            new Field(
                "ownerName",
                "Owner name",
                FieldType.TEXT,
                true,
                null,
                null,
                new FieldValidation(null, null, 2, 120, "[A-Za-z ]+"),
                "party"),
            new Field("notes", "Notes", FieldType.LONGTEXT, false, null, null, null, null),
            new Field(
                "durationMonths",
                "Duration (months)",
                FieldType.INT,
                true,
                null,
                null,
                new FieldValidation(1L, 60L, null, null, null),
                null),
            new Field(
                "rent",
                "Monthly rent",
                FieldType.MONEY,
                true,
                new BigDecimal("15000"),
                null,
                new FieldValidation(0L, null, null, null, null),
                null),
            new Field("startDate", "Start date", FieldType.DATE, true, null, null, null, null),
            new Field(
                "furnished", "Furnished", FieldType.BOOL, false, Boolean.FALSE, null, null, null),
            new Field(
                "regResp",
                "Registration responsibility",
                FieldType.ENUM,
                false,
                null,
                List.of("owner", "tenant"),
                null,
                null));

    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "partiesClause",
                "Between {{ownerName}} ...",
                "furnished == true",
                List.of("ownerName")),
            new Clause.Ref("library-jurisdiction"));

    List<Section> sections =
        List.of(
            new Section(
                "Parties",
                List.of("ownerName", "partiesClause", "notes"),
                false,
                RenderKind.KEYVALUE),
            new Section(
                "Terms",
                List.of("durationMonths", "rent", "startDate", "furnished", "regResp"),
                false,
                RenderKind.KEYVALUE));

    return effectiveOf(fields, clauses, sections);
  }

  private static EffectiveTemplate singleFieldTemplate(FieldType type) {
    List<String> options = type == FieldType.ENUM ? List.of("a", "b") : null;
    Field field = new Field("f", "F", type, true, null, options, null, null);
    Section section = new Section("Only", List.of("f"), false, RenderKind.KEYVALUE);
    return effectiveOf(List.of(field), List.of(), List.of(section));
  }

  /**
   * A template mixing a mandatory field-bearing section (PARTIES), an optional field-bearing
   * section (ANNEXURE), and a clause-only / document-only section whose only entry is a clause id
   * (the "Witnesseth" list) -- which must be omitted from the FormSchema.
   */
  private static EffectiveTemplate mixedTemplate() {
    List<Field> fields =
        List.of(
            new Field("ownerName", "Owner name", FieldType.TEXT, true, null, null, null, null),
            new Field("notes", "Notes", FieldType.LONGTEXT, false, null, null, null, null));

    List<Clause> clauses =
        List.of(
            new Clause.Inline(
                "witnessClause", "Now this Agreement witnesseth ...", null, List.of()));

    List<Section> sections =
        List.of(
            new Section("Parties", List.of("ownerName"), false, RenderKind.PARTIES),
            new Section("Add-ons", List.of("notes"), true, RenderKind.ANNEXURE),
            // Entries are all clause ids -> zero projected fields -> omitted.
            new Section("Witnesseth", List.of("witnessClause"), false, RenderKind.CLAUSES));

    return effectiveOf(fields, clauses, sections);
  }

  private static EffectiveTemplate effectiveOf(
      List<Field> fields, List<Clause> clauses, List<Section> sections) {
    Dimensions dimensions = new Dimensions("TG", "residential");
    Meta meta = new Meta("rental-base", dimensions, 3, TemplateStatus.PUBLISHED, null);
    TemplateDefinition definition =
        new TemplateDefinition(meta, fields, clauses, sections, "hash-abc");
    return new EffectiveTemplate(definition, dimensions, Map.of("rental-base", 3), "hash-abc");
  }

  private static List<FormField> allFields(FormSchema schema) {
    return schema.sections().stream().flatMap(section -> section.fields().stream()).toList();
  }

  private static FormField onlyField(FormSchema schema) {
    List<FormField> fields = allFields(schema);
    assertThat(fields).hasSize(1);
    return fields.get(0);
  }

  private static Map<String, FormField> fieldsByKey(FormSchema schema) {
    return allFields(schema).stream()
        .collect(java.util.stream.Collectors.toMap(FormField::key, field -> field));
  }

  private static FormSection sectionByTitle(FormSchema schema, String title) {
    return schema.sections().stream()
        .filter(section -> section.title().equals(title))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no section titled " + title));
  }
}
