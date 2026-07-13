package in.agreementmitra.documents.template;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * A registry-backed {@link LayerSource}: it decides <b>which</b> layer set answers a {@code (state,
 * type)} by reading the Postgres catalog, then loads the layer <b>bodies from classpath
 * resources</b> via the catalog row's {@code layerSetRef} pointer (design D3). The database never
 * holds a body; it holds the index of what exists and where its layers live.
 *
 * <p>Resolution for {@code (state, type)} finds the <b>published</b> catalog entry for exactly
 * those dimensions; if none exists it raises {@link ResolutionException} -- which the {@code
 * documents.api} surfaces as a 404. This is the catalog acting as the <b>dimension-validation
 * authority</b>: an unknown/unpublished {@code (state, type)} resolves to nothing rather than
 * silently falling back to a national base (the D7 gap). Given the pointer's root it then loads
 * {@code base.yaml} plus the applicable {@code type}/{@code state}/{@code state_type} patches by
 * the same filename convention as {@link ClasspathLayerSource}, so the composed effective template
 * and its content hash are identical to a classpath resolution of the same layer set -- only the
 * lookup source changed.
 *
 * <p><b>Wiring choice:</b> selected by <b>profile</b> (not made the unconditional default), and
 * {@link Primary} within those profiles. Under {@code local}/{@code sandbox} it becomes the primary
 * {@link LayerSource} the {@code TemplateResolver} injects; everywhere else (tests, plain boot) the
 * classpath {@code LayerSource} from {@code TemplateResolutionConfig} stays the sole bean, so no
 * existing resolution path (e.g. form projection over the reference set) changes. Profile-gating is
 * preferred over an unconditional {@code @Primary} precisely so the classpath source remains the
 * zero-registry fallback and form-projection integration tests keep resolving without seeded rows.
 */
@Component
@Primary
@Profile({"local", "sandbox"})
class RegistryLayerSource implements LayerSource {

  private final TemplateCatalogRepository repository;
  private final TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
  private final LayerPatchLoader patchLoader = new LayerPatchLoader();

  RegistryLayerSource(TemplateCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  public LayerSet layersFor(String state, String type) {
    TemplateCatalogEntry entry =
        repository
            .findFirstByStatusAndStateAndTypeOrderByVersionDesc(
                TemplateStatus.PUBLISHED, state, type)
            .orElseThrow(
                () ->
                    new ResolutionException(
                        "no published catalog template for the requested dimensions"));

    String root = normalizeRoot(entry.layerSetRef());

    String basePath = root + "base.yaml";
    if (getClass().getClassLoader().getResource(basePath) == null) {
      throw new ResolutionException("base layer not found for catalog pointer: " + basePath);
    }
    TemplateDefinition base = definitionLoader.loadResource(basePath);
    LayerSet.Base baseLayer =
        new LayerSet.Base(
            new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), basePath),
            base);

    List<LayerSet.Patch> patches = new ArrayList<>();
    addIfPresent(patches, LayerKind.TYPE, root + "type-" + type + ".patch.yaml");
    addIfPresent(patches, LayerKind.STATE, root + "state-" + state + ".patch.yaml");
    addIfPresent(
        patches, LayerKind.STATE_TYPE, root + "state_type-" + state + "-" + type + ".patch.yaml");

    return new LayerSet(baseLayer, patches);
  }

  private void addIfPresent(List<LayerSet.Patch> patches, LayerKind expected, String path) {
    if (getClass().getClassLoader().getResource(path) == null) {
      return;
    }
    LayerPatch patch = patchLoader.loadResource(path);
    if (patch.meta().kind() != expected) {
      throw new ResolutionException(
          "patch " + path + " declares kind " + patch.meta().kind() + ", expected " + expected);
    }
    LayerRef ref =
        new LayerRef(patch.meta().kind(), patch.meta().dimensions(), patch.meta().version(), path);
    patches.add(new LayerSet.Patch(ref, patch));
  }

  private static String normalizeRoot(String layerSetRef) {
    return layerSetRef.endsWith("/") ? layerSetRef : layerSetRef + "/";
  }
}
