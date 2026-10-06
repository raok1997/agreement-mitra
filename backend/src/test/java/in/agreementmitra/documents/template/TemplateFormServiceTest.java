package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.ResourceNotFoundException;
import in.agreementmitra.documents.api.FormSchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link TemplateFormService#findForm} is empty only when no published template exists; every other
 * resolution failure still throws, and {@link TemplateFormService#formFor} keeps its 404 contract.
 */
class TemplateFormServiceTest {

  private static final String INVALID_BASE =
      """
      meta: { id: b, dimensions: { state: IN, type: residential }, version: 1, status: draft }
      fields:
        - { key: rent, label: Rent, type: money, required: true }
      clauses:
        - { id: c1, text: "Rent is {{rent}}." }
      sections:
        - { title: S1, entries: [ rent, c1, ghost ] }
      """;

  private static TemplateFormService service(LayerSource source) {
    return new TemplateFormService(new TemplateResolver(source), new FormProjector());
  }

  private static final LayerSource NOT_PUBLISHED =
      (state, type) -> {
        throw new ResolutionException.NoPublishedTemplate("no published catalog template");
      };

  @Test
  void resolvablePairYieldsItsSchema() {
    Optional<FormSchema> form = service(new ClasspathLayerSource()).findForm("TG", "residential");

    assertThat(form).isPresent();
    assertThat(form.get().sections()).isNotEmpty();
  }

  @Test
  void pairWithNoPublishedTemplateIsEmpty() {
    assertThat(service(NOT_PUBLISHED).findForm("KA", "commercial")).isEmpty();
  }

  @Test
  void formForStillThrowsNotFoundForThatPair() {
    assertThatThrownBy(() -> service(NOT_PUBLISHED).formFor("KA", "commercial"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void malformedLayerStillThrows() {
    LayerSource wrongKind =
        (state, type) -> {
          throw new ResolutionException("patch declares kind STATE, expected TYPE");
        };

    assertThatThrownBy(() -> service(wrongKind).findForm("TG", "residential"))
        .isInstanceOf(ResolutionException.class)
        .isNotInstanceOf(ResolutionException.NoPublishedTemplate.class);
    // formFor keeps its 404 contract for every resolution fault.
    assertThatThrownBy(() -> service(wrongKind).formFor("TG", "residential"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void invalidEffectiveTemplateStillThrows() {
    LayerSource invalid =
        (state, type) -> {
          TemplateDefinition base = new TemplateDefinitionLoader().load(INVALID_BASE);
          LayerRef ref =
              new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), "b");
          return new LayerSource.LayerSet(new LayerSource.LayerSet.Base(ref, base), List.of());
        };

    assertThatThrownBy(() -> service(invalid).findForm("TG", "residential"))
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(ResolutionException.NoPublishedTemplate.class);
  }
}
