package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.documents.api.FormSchema;
import org.junit.jupiter.api.Test;

/**
 * Integration test (task 5.1) over the real {@code sets/formsection} layer set on the classpath:
 * via {@link ClasspathLayerSource} resolve {@code (IN, residential)} end-to-end through the real
 * {@link TemplateResolver} (classpath YAML load + patch composition + re-validation + content hash)
 * and the real {@link FormProjector}, then serialize the projected {@link FormSchema} to JSON with
 * the same Jackson {@link ObjectMapper} the HTTP surface uses. Asserts the served schema carries
 * {@code optional} + {@code renderKind} on a mandatory field-bearing section, surfaces an optional
 * field-bearing section with its render kind, and <b>omits</b> the clause-only (Witnesseth-style)
 * section entirely.
 *
 * <p>Real resource I/O (classpath layer set), no Spring context, no Gotenberg, no Testcontainers --
 * so it runs regardless of Docker, mirroring the sibling {@code
 * OptionalSectionProjectionIntegrationTest}.
 */
class FormSectionSemanticsProjectionIntegrationTest {

  private final TemplateResolver resolver =
      new TemplateResolver(new ClasspathLayerSource("documents/template/testsets/formsection/"));
  private final FormProjector projector = new FormProjector();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void servedSchemaCarriesOptionalAndRenderKindAndOmitsTheClauseOnlySection() throws Exception {
    FormSchema schema = projector.project(resolver.resolve(new Dimensions("IN", "residential")));
    JsonNode body = mapper.readTree(mapper.writeValueAsString(schema));

    // The clause-only "Witnesseth" section (zero field keys) is dropped; the two field-bearing
    // sections remain in authored order.
    assertThat(sectionTitles(body)).containsExactly("Parties", "Add-ons");

    // A mandatory field-bearing section carries optional == false and its declared render kind.
    JsonNode parties = section(body, "Parties");
    assertThat(parties.path("optional").asBoolean()).isFalse();
    assertThat(parties.path("renderKind").asText()).isEqualTo("parties");
    assertThat(fieldKeys(parties)).containsExactly("ownerName", "tenantName");

    // An optional field-bearing section carries optional == true and its declared render kind.
    JsonNode addOns = section(body, "Add-ons");
    assertThat(addOns.path("optional").asBoolean()).isTrue();
    assertThat(addOns.path("renderKind").asText()).isEqualTo("annexure");
    assertThat(fieldKeys(addOns)).containsExactly("extraNote");

    // The new fields are present on the wire (not silently dropped by serialization).
    assertThat(parties.hasNonNull("optional")).isTrue();
    assertThat(parties.hasNonNull("renderKind")).isTrue();
  }

  // --- helpers --------------------------------------------------------------

  private static java.util.List<String> sectionTitles(JsonNode body) {
    java.util.List<String> titles = new java.util.ArrayList<>();
    body.path("sections").forEach(section -> titles.add(section.path("title").asText()));
    return titles;
  }

  private static JsonNode section(JsonNode body, String title) {
    for (JsonNode section : body.path("sections")) {
      if (section.path("title").asText().equals(title)) {
        return section;
      }
    }
    throw new AssertionError("no section titled " + title);
  }

  private static java.util.List<String> fieldKeys(JsonNode section) {
    java.util.List<String> keys = new java.util.ArrayList<>();
    section.path("fields").forEach(field -> keys.add(field.path("key").asText()));
    return keys;
  }
}
