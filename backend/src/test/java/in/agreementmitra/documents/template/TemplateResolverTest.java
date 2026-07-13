package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Composition semantics (7.2), resolution failure modes (7.3), and determinism + identity (7.4) of
 * the {@link TemplateResolver}. Pure unit tests: the {@link LayerSource} is an in-memory stub built
 * from definitions/patches parsed from YAML strings -- no Spring context, no classpath resource, no
 * network.
 */
class TemplateResolverTest {

  private static final String BASE =
      """
      meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
      fields:
        - { key: rent, label: Rent, type: money, required: true }
        - { key: flag, label: Flag, type: bool, required: false, default: false }
      clauses:
        - { id: c1, text: "Rent is {{rent}}." }
        - { id: c2, text: "Flag note.", showWhen: "flag == true" }
      sections:
        - { title: S1, entries: [ rent, c1 ] }
        - { title: S2, entries: [ flag, c2 ] }
      """;

  // --- 7.2 composition semantics ---------------------------------------------

  @Test
  void addFieldAddClauseAndReplaceSectionCompose() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: addField, field: { key: extra, label: Extra, type: text, required: false } }
          - { op: addClause, clause: { id: c3, text: "Extra {{extra}}." } }
          - { op: replaceSection, title: S2, section: { title: S2, entries: [ flag, c2, extra, c3 ] } }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(patch));

    assertThat(fieldKeys(eff)).containsExactly("rent", "flag", "extra");
    assertThat(clauseIds(eff)).contains("c3");
    assertThat(section(eff, "S2").entries()).containsExactly("flag", "c2", "extra", "c3");
  }

  @Test
  void overrideFieldReplacesOnlyGivenMetadataAndKeepsType() {
    String patch =
        """
        meta: { kind: type, dimensions: { type: residential }, version: 1 }
        ops:
          - { op: overrideField, key: flag, required: true, default: true, label: "Is furnished" }
        """;

    Field flag = field(resolve(def(BASE), layer(patch)), "flag");

    assertThat(flag.type()).isEqualTo(FieldType.BOOL);
    assertThat(flag.required()).isTrue();
    assertThat(flag.defaultValue()).isEqualTo(true);
    assertThat(flag.label()).isEqualTo("Is furnished");
  }

  @Test
  void replaceAndRemoveClauseCompose() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "Monthly rent {{rent}}." } }
          - { op: removeClause, id: c2 }
          - { op: replaceSection, title: S2, section: { title: S2, entries: [ flag ] } }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(patch));

    assertThat(inlineClause(eff, "c1").text()).isEqualTo("Monthly rent {{rent}}.");
    assertThat(clauseIds(eff)).doesNotContain("c2");
  }

  @Test
  void addRemoveAndReorderSectionsCompose() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: addSection, section: { title: S3, entries: [ rent ] } }
          - { op: removeSection, title: S1 }
          - { op: reorderSections, order: [ S3, S2 ] }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(patch));

    assertThat(eff.template().sections().stream().map(Section::title)).containsExactly("S3", "S2");
  }

  @Test
  void reorderEntriesReordersWithinASection() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: reorderEntries, title: S1, order: [ c1, rent ] }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(patch));

    assertThat(section(eff, "S1").entries()).containsExactly("c1", "rent");
  }

  @Test
  void operationsWithinALayerApplyInAuthorOrderLastWins() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "first {{rent}}" } }
          - { op: replaceClause, id: c1, clause: { id: c1, text: "second {{rent}}" } }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(patch));

    assertThat(inlineClause(eff, "c1").text()).isEqualTo("second {{rent}}");
  }

  @Test
  void aLaterPrecedenceLayerWinsOverAnEarlierOne() {
    String typePatch =
        """
        meta: { kind: type, dimensions: { type: residential }, version: 1 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "TYPE {{rent}}" } }
        """;
    String stateTypePatch =
        """
        meta: { kind: state_type, dimensions: { state: TG, type: residential }, version: 1 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "STATE_TYPE {{rent}}" } }
        """;

    EffectiveTemplate eff = resolve(def(BASE), layer(typePatch), layer(stateTypePatch));

    assertThat(inlineClause(eff, "c1").text()).isEqualTo("STATE_TYPE {{rent}}");
  }

  // --- 7.3 resolution failure modes ------------------------------------------

  @Test
  void anOperationTargetingAMissingElementFailsNamingLayerAndTarget() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: replaceClause, id: ghost, clause: { id: ghost, text: "x" } }
        """;

    assertThatThrownBy(() -> resolve(def(BASE), layer(patch)))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("state:TG")
        .hasMessageContaining("ghost");
  }

  @Test
  void removingAFieldThatASurvivingSlotReferencesFailsRejectOrNothing() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: removeField, key: rent }
        """;

    // c1 still references {{rent}} and S1 still lists rent -- the orphan is caught on
    // re-validation.
    assertThatThrownBy(() -> resolve(def(BASE), layer(patch)))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("rent");
  }

  @Test
  void removingAClauseThatASectionStillListsFailsRejectOrNothing() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: removeClause, id: c1 }
        """;

    // S1 still lists c1 -- the orphaned section entry is caught on re-validation.
    assertThatThrownBy(() -> resolve(def(BASE), layer(patch)))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("c1");
  }

  @Test
  void aComposedResultViolatingADefinitionInvariantFails() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: addField, field: { key: rent, label: Dup, type: money, required: false } }
        """;

    assertThatThrownBy(() -> resolve(def(BASE), layer(patch)))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("rent");
  }

  @Test
  void failureMessagesCarryNoDataValue() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: removeField, key: rent }
        """;

    Throwable thrown = catchThrowable(() -> resolve(def(BASE), layer(patch)));

    assertThat(thrown).isInstanceOf(ResolutionException.class);
    // Cites the orphaned slot's field key, but never a clause's literal text content.
    assertThat(thrown.getMessage()).doesNotContain("Rent is");
  }

  // --- 7.4 determinism + identity --------------------------------------------

  @Test
  void repeatResolutionIsByteIdentical() {
    String patch =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 2 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "Rent now {{rent}}." } }
        """;

    EffectiveTemplate first = resolve(def(BASE), layer(patch));
    EffectiveTemplate second = resolve(def(BASE), layer(patch));

    assertThat(second.contentHash()).isEqualTo(first.contentHash());
    assertThat(canonical(second)).isEqualTo(canonical(first));
    assertThat(first.contentHash()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(first.provenance()).containsEntry("base", 1).containsEntry("state:TG", 2);
  }

  @Test
  void aContentBearingLayerEditChangesTheHashAndVersionMap() {
    String v1 =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "one {{rent}}" } }
        """;
    String v2 =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 2 }
        ops:
          - { op: replaceClause, id: c1, clause: { id: c1, text: "two {{rent}}" } }
        """;

    EffectiveTemplate first = resolve(def(BASE), layer(v1));
    EffectiveTemplate second = resolve(def(BASE), layer(v2));

    assertThat(second.contentHash()).isNotEqualTo(first.contentHash());
    assertThat(first.provenance()).containsEntry("state:TG", 1);
    assertThat(second.provenance()).containsEntry("state:TG", 2);
  }

  @Test
  void aBaseOnlyResolutionMatchesTheDefinitionHashOfThatBase() {
    TemplateDefinition base = def(BASE);

    EffectiveTemplate eff = resolve(base);

    assertThat(eff.contentHash()).isEqualTo(base.contentHash());
    assertThat(eff.provenance()).containsExactly(java.util.Map.entry("base", 1));
  }

  // --- helpers ---------------------------------------------------------------

  private static TemplateDefinition def(String yaml) {
    return new TemplateDefinitionLoader().load(yaml);
  }

  private static LayerSource.LayerSet.Patch layer(String yaml) {
    LayerPatch patch = new LayerPatchLoader().load(yaml);
    LayerRef ref =
        new LayerRef(
            patch.meta().kind(), patch.meta().dimensions(), patch.meta().version(), "layer");
    return new LayerSource.LayerSet.Patch(ref, patch);
  }

  private static EffectiveTemplate resolve(
      TemplateDefinition base, LayerSource.LayerSet.Patch... patches) {
    LayerRef baseRef =
        new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), "base");
    LayerSource.LayerSet set =
        new LayerSource.LayerSet(new LayerSource.LayerSet.Base(baseRef, base), List.of(patches));
    LayerSource source = (state, type) -> set;
    return new TemplateResolver(source).resolve(base.meta().dimensions());
  }

  private static String canonical(EffectiveTemplate eff) {
    TemplateDefinition t = eff.template();
    return CanonicalJson.canonicalize(t.meta(), t.fields(), t.clauses(), t.sections());
  }

  private static List<String> fieldKeys(EffectiveTemplate eff) {
    return eff.template().fields().stream().map(Field::key).toList();
  }

  private static Field field(EffectiveTemplate eff, String key) {
    return eff.template().fields().stream()
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> clauseIds(EffectiveTemplate eff) {
    return eff.template().clauses().stream()
        .filter(Clause.Inline.class::isInstance)
        .map(c -> ((Clause.Inline) c).id())
        .toList();
  }

  private static Clause.Inline inlineClause(EffectiveTemplate eff, String id) {
    return eff.template().clauses().stream()
        .filter(c -> c instanceof Clause.Inline i && i.id().equals(id))
        .map(Clause.Inline.class::cast)
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
