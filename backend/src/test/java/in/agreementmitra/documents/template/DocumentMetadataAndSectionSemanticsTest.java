package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Module M0 (template-document-metadata): the loader parses the new declarative additions
 * (meta.document header, per-section optional + render kind), applies defaults, validates the
 * executionLine slots, and the content hash carries the new fields deterministically. Pure unit
 * tests -- no Spring, no I/O beyond the classpath schema.
 */
class DocumentMetadataAndSectionSemanticsTest {

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  private static final String WITH_DOCUMENT =
      """
      meta:
        id: b
        dimensions: { state: IN, type: residential }
        version: 1
        status: published
        document:
          title: Rental Agreement
          subtitle: Residential Tenancy (Leave and Licence)
          executionLine: "Executed on {{agreementDate}}."
      fields:
        - { key: agreementDate, label: Agreement date, type: date, required: false }
        - { key: ownerName, label: Owner, type: text, required: true }
      clauses:
        - { id: recital, text: "Owner is {{ownerName}}." }
      sections:
        - { title: Owner, entries: [ ownerName, recital ], render: parties }
        - { title: Meta, entries: [ agreementDate ], optional: true }
      """;

  @Test
  void loadsDocumentBlockOptionalAndRenderKind() {
    TemplateDefinition def = loader.load(WITH_DOCUMENT);

    DocumentMeta document = def.meta().document();
    assertThat(document).isNotNull();
    assertThat(document.title()).isEqualTo("Rental Agreement");
    assertThat(document.subtitle()).isEqualTo("Residential Tenancy (Leave and Licence)");
    assertThat(document.executionLine()).isEqualTo("Executed on {{agreementDate}}.");

    Section owner = section(def, "Owner");
    assertThat(owner.render()).isEqualTo(RenderKind.PARTIES);
    assertThat(owner.optional()).isFalse();

    Section meta = section(def, "Meta");
    assertThat(meta.optional()).isTrue();
    assertThat(meta.render()).isEqualTo(RenderKind.KEYVALUE); // defaulted
  }

  @Test
  void defaultsWhenOmitted() {
    String yaml =
        """
        meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ] }
        """;

    TemplateDefinition def = loader.load(yaml);

    assertThat(def.meta().document()).isNull();
    assertThat(section(def, "S").optional()).isFalse();
    assertThat(section(def, "S").render()).isEqualTo(RenderKind.KEYVALUE);
  }

  @Test
  void documentBlockMissingTitleIsRejected() {
    String yaml =
        """
        meta:
          id: b
          dimensions: { state: IN, type: residential }
          version: 1
          status: draft
          document: { subtitle: "no title" }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ] }
        """;

    assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void unknownRenderKindIsRejected() {
    String yaml =
        """
        meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ], render: banner }
        """;

    assertThatThrownBy(() -> loader.load(yaml)).isInstanceOf(TemplateDefinitionException.class);
  }

  @Test
  void executionLineSlotMustNameADeclaredField() {
    String yaml =
        """
        meta:
          id: b
          dimensions: { state: IN, type: residential }
          version: 1
          status: draft
          document: { title: T, executionLine: "On {{ghost}}." }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ] }
        """;

    assertThatThrownBy(() -> loader.load(yaml))
        .isInstanceOf(TemplateDefinitionException.class)
        .hasMessageContaining("ghost");
  }

  @Test
  void contentHashMovesWithNewFieldsButIsStableAcrossDefaultedOmission() {
    // Adding a document block changes the hash: the same definition without the document block.
    TemplateDefinition withDoc = loader.load(WITH_DOCUMENT);
    String plain =
        """
        meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: published }
        fields:
          - { key: agreementDate, label: Agreement date, type: date, required: false }
          - { key: ownerName, label: Owner, type: text, required: true }
        clauses:
          - { id: recital, text: "Owner is {{ownerName}}." }
        sections:
          - { title: Owner, entries: [ ownerName, recital ], render: parties }
          - { title: Meta, entries: [ agreementDate ], optional: true }
        """;
    assertThat(withDoc.contentHash()).isNotEqualTo(loader.load(plain).contentHash());

    // A section explicitly stating the defaults hashes identically to one omitting them.
    String explicitDefaults =
        """
        meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ], optional: false, render: keyvalue }
        """;
    String omittedDefaults =
        """
        meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
        fields:
          - { key: f, label: F, type: text, required: true }
        sections:
          - { title: S, entries: [ f ] }
        """;
    assertThat(loader.load(explicitDefaults).contentHash())
        .isEqualTo(loader.load(omittedDefaults).contentHash());
  }

  private static Section section(TemplateDefinition def, String title) {
    return def.sections().stream().filter(s -> s.title().equals(title)).findFirst().orElseThrow();
  }
}
