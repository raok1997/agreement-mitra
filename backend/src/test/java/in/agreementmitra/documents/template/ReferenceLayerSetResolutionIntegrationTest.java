package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-pipeline integration test (task 8.1) over the real reference layer set on the classpath: via
 * {@link ClasspathLayerSource}, resolve {@code (TG, residential)} end-to-end -- load base + patches
 * from the classpath, compose in precedence order, re-validate, showWhen-validate, canonicalize,
 * and SHA-256 hash. Asserts the effective template reflects every operation, resolution is stable
 * across two calls, the content hash is deterministic, and the reserved {@code language} layer is
 * ignored. Real resource I/O, but no Spring context or Testcontainers -- it runs regardless of
 * Docker.
 *
 * <p>Guards schema/record/canonicalizer drift across the {@code template-definition} and {@code
 * template-resolution} capabilities: the base is a definition, the patches are layer patches, and
 * the effective template must hash with the same canonicalizer as a plain definition.
 */
class ReferenceLayerSetResolutionIntegrationTest {

  private static final String BASE_RESOURCE = "documents/template/examples/layers/base.yaml";

  private final TemplateResolver resolver = new TemplateResolver(new ClasspathLayerSource());

  @Test
  void resolvesTheTelanganaResidentialLayerSetReflectingEveryOperation() {
    EffectiveTemplate eff = resolver.resolve(new Dimensions("TG", "residential"));

    // addField (state) + overrideField (type: registration now required, defaulted).
    assertThat(fieldKeys(eff)).contains("stampDuty");
    Field registration = field(eff, "registrationResponsibility");
    assertThat(registration.required()).isTrue();
    assertThat(registration.defaultValue()).isEqualTo("owner");

    // replaceClause (state+type, last-wins) with a showWhen introduced via a patch; removeClause.
    assertThat(inlineClause(eff, "rent").text()).contains("in advance");
    assertThat(inlineClause(eff, "rent").showWhen()).isEqualTo("monthlyRent > 0");
    assertThat(clauseIds(eff)).contains("use", "tgStamp").doesNotContain("furnishing");

    // addSection (state) + replaceSection (type/state+type) + reorderSections (state+type).
    assertThat(eff.template().sections().stream().map(Section::title))
        .containsExactly("Parties", "Financial", "Term", "Statutory");
    assertThat(section(eff, "Financial").entries()).containsExactly("monthlyRent", "rent", "use");
    assertThat(section(eff, "Term").entries())
        .containsExactly("durationMonths", "furnished", "term");
    assertThat(section(eff, "Statutory").entries()).containsExactly("stampDuty", "tgStamp");

    // Provenance pins every contributing layer + version; the reserved language layer is absent.
    assertThat(eff.provenance())
        .containsEntry("base", 1)
        .containsEntry("type:residential", 1)
        .containsEntry("state:TG", 1)
        .containsEntry("state_type:TG:residential", 1)
        .doesNotContainKey("language");
  }

  @Test
  void theReservedLanguageLayerIsNotApplied() {
    EffectiveTemplate eff = resolver.resolve(new Dimensions("TG", "residential"));

    // language-en.patch.yaml sits in the resource set but must never be loaded or applied.
    assertThat(clauseIds(eff)).doesNotContain("langNote");
  }

  @Test
  void resolutionIsStableAndTheHashIsDeterministicAcrossTwoCalls() {
    EffectiveTemplate first = resolver.resolve(new Dimensions("TG", "residential"));
    EffectiveTemplate second = resolver.resolve(new Dimensions("TG", "residential"));

    assertThat(second.contentHash()).isEqualTo(first.contentHash());
    assertThat(first.contentHash()).hasSize(64).matches("[0-9a-f]{64}");
  }

  @Test
  void aBaseOnlyResolutionEqualsTheDefinitionHashOfTheBase() {
    // (KA, commercial) matches no type/state/state_type patch file, so only the base applies.
    EffectiveTemplate eff = resolver.resolve(new Dimensions("KA", "commercial"));

    TemplateDefinition base = new TemplateDefinitionLoader().loadResource(BASE_RESOURCE);
    assertThat(eff.contentHash()).isEqualTo(base.contentHash());
    assertThat(eff.provenance()).containsOnlyKeys("base");
  }

  // --- helpers ---------------------------------------------------------------

  private static java.util.List<String> fieldKeys(EffectiveTemplate eff) {
    return eff.template().fields().stream().map(Field::key).toList();
  }

  private static Field field(EffectiveTemplate eff, String key) {
    return eff.template().fields().stream()
        .filter(f -> f.key().equals(key))
        .findFirst()
        .orElseThrow();
  }

  private static java.util.List<String> clauseIds(EffectiveTemplate eff) {
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
