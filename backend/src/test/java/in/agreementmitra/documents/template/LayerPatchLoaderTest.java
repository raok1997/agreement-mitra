package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

/**
 * Layer-patch loading semantics (task 7.1): each supported operation binds, and the two structural
 * guards -- an unsupported op and an {@code overrideField} that changes a field's {@code type} --
 * are rejected reject-or-nothing with a location-only error. Pure unit tests over in-memory YAML
 * strings; no Spring context, no classpath resource, no network.
 */
class LayerPatchLoaderTest {

  private final LayerPatchLoader loader = new LayerPatchLoader();

  @Test
  void everySupportedOperationBinds() {
    String yaml =
        """
        meta: { kind: state_type, dimensions: { state: TG, type: residential }, version: 3 }
        ops:
          - { op: addField, field: { key: stampDuty, label: "Stamp duty", type: money, required: true } }
          - { op: overrideField, key: rent, required: true, default: 1, label: "Monthly rent" }
          - { op: removeField, key: obsolete }
          - { op: addClause, after: rent, clause: { id: stamp, text: "Stamp {{stampDuty}}." } }
          - { op: replaceClause, id: rent, clause: { id: rent, text: "Rent {{stampDuty}}." } }
          - { op: removeClause, id: old }
          - { op: addSection, section: { title: Statutory, entries: [ stampDuty, stamp ] } }
          - { op: replaceSection, title: Money, section: { title: Money, entries: [ rent ] } }
          - { op: removeSection, title: Legacy }
          - { op: reorderSections, order: [ Money, Statutory ] }
          - { op: reorderEntries, title: Money, order: [ rent ] }
        """;

    LayerPatch patch = loader.load(yaml);

    assertThat(patch.meta().kind()).isEqualTo(LayerKind.STATE_TYPE);
    assertThat(patch.meta().dimensions().state()).isEqualTo("TG");
    assertThat(patch.meta().dimensions().type()).isEqualTo("residential");
    assertThat(patch.meta().version()).isEqualTo(3);
    assertThat(patch.ops())
        .hasSize(11)
        .hasOnlyElementsOfTypes(
            Op.AddField.class,
            Op.OverrideField.class,
            Op.RemoveField.class,
            Op.AddClause.class,
            Op.ReplaceClause.class,
            Op.RemoveClause.class,
            Op.AddSection.class,
            Op.ReplaceSection.class,
            Op.RemoveSection.class,
            Op.ReorderSections.class,
            Op.ReorderEntries.class);
    assertThat(patch.ops().get(0)).isInstanceOf(Op.AddField.class);
    assertThat(patch.ops().get(10)).isInstanceOf(Op.ReorderEntries.class);
  }

  @Test
  void overrideFieldCarriesOnlyThePresentOverrides() {
    String yaml =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: overrideField, key: registrationResponsibility, required: true, default: owner }
        """;

    Op.OverrideField op = (Op.OverrideField) loader.load(yaml).ops().get(0);

    assertThat(op.key()).isEqualTo("registrationResponsibility");
    assertThat(op.required()).isTrue();
    assertThat(op.defaultPresent()).isTrue();
    assertThat(op.defaultValue()).isEqualTo("owner");
    assertThat(op.options()).isNull();
    assertThat(op.label()).isNull();
    assertThat(op.group()).isNull();
    assertThat(op.validation()).isNull();
  }

  @Test
  void aTypeOnlyPatchMayOmitTheStateDimension() {
    String yaml =
        """
        meta: { kind: type, dimensions: { type: residential }, version: 1 }
        ops:
          - { op: removeClause, id: x }
        """;

    LayerPatch patch = loader.load(yaml);

    assertThat(patch.meta().kind()).isEqualTo(LayerKind.TYPE);
    assertThat(patch.meta().dimensions().type()).isEqualTo("residential");
    assertThat(patch.meta().dimensions().state()).isNull();
  }

  @Test
  void anUnsupportedOperationIsRejected() {
    String yaml =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: renameField, key: rent, to: monthlyRent }
        """;

    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("structural");
  }

  @Test
  void anOverrideFieldChangingTypeIsRejected() {
    String yaml =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: overrideField, key: rent, type: text }
        """;

    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(ResolutionException.class)
        .hasMessageContaining("structural");
  }

  @Test
  void errorMessagesCarryNoDataValue() {
    // A malformed override (type change) must be located structurally, never echoing the value.
    String yaml =
        """
        meta: { kind: state, dimensions: { state: TG }, version: 1 }
        ops:
          - { op: overrideField, key: rent, type: longtext }
        """;

    Throwable thrown = catchThrowable(() -> loader.load(yaml));

    assertThat(thrown).isInstanceOf(ResolutionException.class);
    assertThat(thrown.getMessage()).doesNotContain("longtext");
  }
}
