package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Full-pipeline integration test over the real reference fixture: loads {@code
 * documents/template/examples/residential-rental.yaml} from the classpath through the entire chain
 * (YAML parse -&gt; structural JSON-Schema validation -&gt; semantic validation -&gt; canonical
 * JSON -&gt; SHA-256 hash). Exercises the checked-in schema and the real definition together, so it
 * guards against schema/record drift. Real resource I/O, but no Spring context or Testcontainers --
 * it runs regardless of Docker.
 */
class ResidentialRentalDefinitionIntegrationTest {

  private static final String RESOURCE = "documents/template/examples/residential-rental.yaml";

  private final TemplateDefinitionLoader loader = new TemplateDefinitionLoader();

  @Test
  void referenceDefinitionLoadsCleanlyThroughTheWholeChain() {
    TemplateDefinition def = loader.loadResource(RESOURCE);

    assertThat(def.meta().id()).isEqualTo("residential-rental");
    assertThat(def.meta().dimensions().state()).isEqualTo("IN");
    assertThat(def.meta().dimensions().type()).isEqualTo("residential");
    assertThat(def.meta().version()).isEqualTo(1);
    assertThat(def.meta().status()).isEqualTo(TemplateStatus.DRAFT);

    assertThat(def.fields()).isNotEmpty();
    assertThat(def.clauses()).isNotEmpty();
    assertThat(def.sections()).isNotEmpty();

    // Every clause slot resolves to a declared field, and every section entry resolves -- proven by
    // the fact that semantic validation passed. Spot-check the money slot binding.
    Clause.Inline rent =
        def.clauses().stream()
            .filter(c -> c instanceof Clause.Inline i && i.id().equals("rent"))
            .map(Clause.Inline.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(rent.slots()).contains("monthlyRent");
  }

  @Test
  void referenceDefinitionCanonicalFormAndHashAreDeterministic() {
    TemplateDefinition first = loader.loadResource(RESOURCE);
    TemplateDefinition second = loader.loadResource(RESOURCE);

    String canonicalFirst =
        CanonicalJson.canonicalize(first.meta(), first.fields(), first.clauses(), first.sections());
    String canonicalSecond =
        CanonicalJson.canonicalize(
            second.meta(), second.fields(), second.clauses(), second.sections());

    assertThat(canonicalSecond).isEqualTo(canonicalFirst);
    assertThat(second.contentHash()).isEqualTo(first.contentHash());
    assertThat(first.contentHash()).hasSize(64).matches("[0-9a-f]{64}");
  }
}
