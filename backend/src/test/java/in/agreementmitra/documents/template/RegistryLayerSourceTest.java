package in.agreementmitra.documents.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The registry-backed {@link LayerSource} over a mocked repository: a selected published id
 * resolves to the ordered layer set via the row's {@code layerSetRef} pointer, bodies come from the
 * resource seam (not the repo), and an unknown/unpublished {@code (state, type)} yields no layer
 * set.
 */
class RegistryLayerSourceTest {

  private final TemplateCatalogRepository repo = mock(TemplateCatalogRepository.class);
  private final RegistryLayerSource source = new RegistryLayerSource(repo);

  private static TemplateCatalogEntry published(String state, String type) {
    return TemplateCatalogEntry.create(
        "Ref (" + state + ")",
        "blurb",
        type,
        state,
        "en",
        1,
        TemplateStatus.PUBLISHED,
        "documents/template/examples/layers/");
  }

  @Test
  void selectedPublishedTemplateResolvesLayersViaPointer() {
    when(repo.findFirstByStatusAndStateAndTypeOrderByVersionDesc(
            TemplateStatus.PUBLISHED, "TG", "residential"))
        .thenReturn(Optional.of(published("TG", "residential")));

    LayerSource.LayerSet set = source.layersFor("TG", "residential");

    // Base loaded from the pointer's resources.
    assertThat(set.base().ref().kind()).isEqualTo(LayerKind.BASE);
    assertThat(set.base().ref().resourcePath()).endsWith("base.yaml");
    // (TG, residential) picks up the type, state-TG, and state_type-TG-residential patches in
    // order.
    assertThat(set.patches())
        .extracting(p -> p.ref().kind())
        .containsExactly(LayerKind.TYPE, LayerKind.STATE, LayerKind.STATE_TYPE);
  }

  @Test
  void nationalDimensionsResolveOnlyBaseAndTypePatch() {
    when(repo.findFirstByStatusAndStateAndTypeOrderByVersionDesc(
            TemplateStatus.PUBLISHED, "IN", "residential"))
        .thenReturn(Optional.of(published("IN", "residential")));

    LayerSource.LayerSet set = source.layersFor("IN", "residential");

    assertThat(set.patches())
        .extracting(p -> p.ref().kind())
        .containsExactly(LayerKind.TYPE); // no state-IN / state_type layer files exist
  }

  @Test
  void unknownDimensionsYieldNoLayerSet() {
    when(repo.findFirstByStatusAndStateAndTypeOrderByVersionDesc(
            TemplateStatus.PUBLISHED, "KA", "commercial"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> source.layersFor("KA", "commercial"))
        .isInstanceOf(ResolutionException.class);
  }
}
