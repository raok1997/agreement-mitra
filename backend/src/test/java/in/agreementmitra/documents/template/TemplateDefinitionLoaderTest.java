package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * Loader semantics: field / clause / section validation and reject-or-nothing error hygiene. Pure
 * unit tests over in-memory YAML strings -- no Spring context, no classpath fixture, no network.
 */
class TemplateDefinitionLoaderTest {

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  /** A minimal, well-formed definition. Tests below vary one part of it. */
  private static String validYaml() {
    return """
        meta:
          id: t
          dimensions: { state: IN, type: residential }
          version: 1
          status: draft
        fields:
          - { key: monthlyRent, label: Rent, type: money, required: true }
        clauses:
          - { id: rent, text: "Rent is {{monthlyRent}}." }
        sections:
          - { title: Money, entries: [ monthlyRent, rent ] }
        """;
  }

  // --- 5.2 field semantics ---------------------------------------------------

  @Test
  void typedFieldWithValidationMetadataLoads() {
    String yaml =
        """
        meta: { id: t, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: lockInMonths, label: Lock-in, type: int, required: false, default: 6,
              validation: { min: 0, max: 60 } }
        sections:
          - { title: Terms, entries: [ lockInMonths ] }
        """;

    TemplateDefinition def = loader.load(yaml);

    Field field = def.fields().get(0);
    assertThat(field.type()).isEqualTo(FieldType.INT);
    assertThat(field.required()).isFalse();
    assertThat(field.defaultValue()).isEqualTo(6L);
    assertThat(field.validation().min()).isEqualTo(0L);
    assertThat(field.validation().max()).isEqualTo(60L);
  }

  @Test
  void unknownFieldTypeIsRejected() {
    String yaml = validYaml().replace("type: money", "type: phone");
    assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void enumFieldWithoutOptionsIsRejected() {
    String yaml = validYaml().replace("type: money, required: true", "type: enum, required: true");
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("monthlyRent");
  }

  @Test
  void nonEnumFieldWithOptionsIsRejected() {
    String yaml =
        validYaml()
            .replace(
                "type: money, required: true", "type: money, required: true, options: [ a, b ]");
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("monthlyRent");
  }

  @Test
  void duplicateFieldKeysAreRejected() {
    String yaml =
        """
        meta: { id: t, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: rent, label: Rent, type: money, required: true }
          - { key: rent, label: Rent again, type: money, required: false }
        sections:
          - { title: Money, entries: [ rent ] }
        """;
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("rent");
  }

  @Test
  void defaultInconsistentWithTypeIsRejected() {
    String yaml =
        validYaml()
            .replace(
                "type: money, required: true", "type: int, required: false, default: notAnInt");
    assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void enumDefaultNotAmongOptionsIsRejected() {
    String yaml =
        validYaml()
            .replace(
                "type: money, required: true",
                "type: enum, required: false, options: [ a, b ], default: c");
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("monthlyRent");
  }

  // --- 5.3 clause semantics --------------------------------------------------

  @Test
  void inlineClauseWithValidSlotRecordsTheSlotReference() {
    TemplateDefinition def = loader.load(validYaml());

    Clause.Inline rent = (Clause.Inline) def.clauses().get(0);
    assertThat(rent.id()).isEqualTo("rent");
    assertThat(rent.text()).contains("{{monthlyRent}}");
    assertThat(rent.slots()).containsExactly("monthlyRent");
  }

  @Test
  void slotReferencingUndeclaredFieldIsRejectedNamingClauseAndSlot() {
    String yaml = validYaml().replace("{{monthlyRent}}", "{{notAField}}");
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("rent")
        .hasMessageContaining("notAField");
  }

  @Test
  void refClauseLoadsVerbatimAndIsNotResolved() {
    String yaml =
        """
        meta: { id: t, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: rentAmt, label: Rent, type: money, required: true }
        clauses:
          - { ref: clause-lib/late-payment }
        sections:
          - { title: Money, entries: [ rentAmt ] }
        """;

    TemplateDefinition def = loader.load(yaml);

    assertThat(def.clauses()).hasSize(1);
    assertThat(def.clauses().get(0)).isInstanceOf(Clause.Ref.class);
    assertThat(((Clause.Ref) def.clauses().get(0)).ref()).isEqualTo("clause-lib/late-payment");
  }

  @Test
  void showWhenIsCarriedVerbatimAndNeverEvaluatedOrIdentifierChecked() {
    String yaml =
        validYaml()
            .replace(
                "{ id: rent, text: \"Rent is {{monthlyRent}}.\" }",
                "{ id: rent, text: \"Rent is {{monthlyRent}}.\", showWhen: \"escalationPct > 0\" }");

    TemplateDefinition def = loader.load(yaml);

    // escalationPct is not a declared field, yet the clause loads: showWhen is opaque here.
    Clause.Inline rent = (Clause.Inline) def.clauses().get(0);
    assertThat(rent.showWhen()).isEqualTo("escalationPct > 0");
  }

  // --- 5.4 section semantics -------------------------------------------------

  @Test
  void sectionPreservesEntryOrderAndResolvesEntries() {
    TemplateDefinition def = loader.load(validYaml());

    Section section = def.sections().get(0);
    assertThat(section.title()).isEqualTo("Money");
    assertThat(section.entries()).containsExactly("monthlyRent", "rent");
  }

  @Test
  void unresolvedSectionEntryIsRejectedNamingSectionAndEntry() {
    String yaml = validYaml().replace("entries: [ monthlyRent, rent ]", "entries: [ ghost ]");
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("Money")
        .hasMessageContaining("ghost");
  }

  // --- 5.5 reject-or-nothing + error hygiene ---------------------------------

  @Test
  void structuralFaultIsRejected() {
    String yaml =
        """
        fields:
          - { key: rent, label: Rent, type: money }
        sections:
          - { title: Money, entries: [ rent ] }
        """; // missing required `meta`
    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("structural");
  }

  @Test
  void semanticFaultIsRejected() {
    String yaml = validYaml().replace("{{monthlyRent}}", "{{missing}}");
    assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void errorMessagesCarryNoDataValue() {
    // A type-inconsistent default: the message must name the field key but never echo the value.
    String yaml =
        validYaml()
            .replace(
                "type: money, required: true", "type: int, required: false, default: sevenHundred");

    Throwable thrown = catchThrowable(() -> loader.load(yaml));

    assertThat(thrown)
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("monthlyRent");
    assertThat(thrown.getMessage()).doesNotContain("sevenHundred");
  }
}
