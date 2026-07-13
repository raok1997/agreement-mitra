package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Canonical form, content hash, and model immutability (unit; in-memory YAML). Proves the hash is a
 * pure function of content: equivalent-but-differently-formatted inputs hash identically, and any
 * content change moves the hash.
 */
class CanonicalJsonTest {

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  // Same definition, block style, canonical key order, no comments.
  private static final String PLAIN =
      """
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

  // The SAME definition: reordered object keys, extra whitespace, and comments.
  private static final String REORDERED =
      """
      # a differently-formatted but equivalent definition
      fields:
        - { type: money, required: true, label: Rent, key: monthlyRent }   # reordered keys
      sections:
        - { entries: [ monthlyRent, rent ], title: Money }
      clauses:
        - { text: "Rent is {{monthlyRent}}.", id: rent }
      meta:
        status: draft
        version: 1
        dimensions: { type: residential, state: IN }
        id: t
      """;

  @Test
  void equivalentDefinitionsProduceEqualCanonicalJsonAndHash() {
    TemplateDefinition a = loader.load(PLAIN);
    TemplateDefinition b = loader.load(REORDERED);

    String canonicalA = CanonicalJson.canonicalize(a.meta(), a.fields(), a.clauses(), a.sections());
    String canonicalB = CanonicalJson.canonicalize(b.meta(), b.fields(), b.clauses(), b.sections());

    assertThat(canonicalB).isEqualTo(canonicalA);
    assertThat(b.contentHash()).isEqualTo(a.contentHash());
    assertThat(a.identity()).isEqualTo(new TemplateDefinition.Identity("t", 1, a.contentHash()));
  }

  @Test
  void aContentChangeChangesTheHash() {
    TemplateDefinition original = loader.load(PLAIN);
    TemplateDefinition flipped = loader.load(PLAIN.replace("required: true", "required: false"));

    assertThat(flipped.contentHash()).isNotEqualTo(original.contentHash());
  }

  @Test
  void contentHashIsAStableSha256Hex() {
    String hash = loader.load(PLAIN).contentHash();
    assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    // Deterministic across loads.
    assertThat(loader.load(PLAIN).contentHash()).isEqualTo(hash);
  }

  @Test
  void loadedModelIsImmutable() {
    TemplateDefinition def = loader.load(PLAIN);

    assertThatThrownBy(() -> def.fields().add(def.fields().get(0)))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> def.clauses().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> def.sections().get(0).entries().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
