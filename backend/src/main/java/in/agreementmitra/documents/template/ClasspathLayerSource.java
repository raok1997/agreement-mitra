package in.agreementmitra.documents.template;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LayerSource} backed by a layer set on the classpath, under {@value #DEFAULT_ROOT} by
 * default (an explicit root may be supplied -- e.g. to resolve the production set in a test).
 * Layers are discovered by a fixed filename convention (a classpath directory cannot be reliably
 * enumerated across jar/exploded layouts, so each candidate is probed by name):
 *
 * <ul>
 *   <li>{@code base.yaml} -- the national base, a full {@link TemplateDefinition} (required);
 *   <li>{@code type-<type>.patch.yaml} -- the type layer (optional);
 *   <li>{@code state-<state>.patch.yaml} -- the state layer (optional);
 *   <li>{@code state_type-<state>-<type>.patch.yaml} -- the state+type layer (optional).
 * </ul>
 *
 * <p>Present patches are returned in the fixed precedence order {@code type -> state ->
 * state_type}; a {@code language} layer is never loaded (reserved dimension). This is the seam a
 * database-backed registry replaces later; the resolver is unaffected.
 */
final class ClasspathLayerSource implements LayerSource {

  private static final String DEFAULT_ROOT = "documents/template/examples/layers/";

  private final String root;
  private final TemplateDefinitionLoader definitionLoader = new TemplateDefinitionLoader();
  private final LayerPatchLoader patchLoader = new LayerPatchLoader();

  /** Resolves the reference fixture layer set (the plain-profile default). */
  ClasspathLayerSource() {
    this(DEFAULT_ROOT);
  }

  /** Resolves an explicit layer-set root (trailing slash optional). */
  ClasspathLayerSource(String root) {
    this.root = root.endsWith("/") ? root : root + "/";
  }

  @Override
  public LayerSet layersFor(String state, String type) {
    String basePath = root + "base.yaml";
    if (getClass().getClassLoader().getResource(basePath) == null) {
      throw new ResolutionException("base layer not found: " + basePath);
    }
    TemplateDefinition base = definitionLoader.loadResource(basePath);
    LayerSet.Base baseLayer =
        new LayerSet.Base(
            new LayerRef(LayerKind.BASE, base.meta().dimensions(), base.meta().version(), basePath),
            base);

    List<LayerSet.Patch> patches = new ArrayList<>();
    addIfPresent(patches, LayerKind.TYPE, root + "type-" + type + ".patch.yaml", state, type);
    addIfPresent(patches, LayerKind.STATE, root + "state-" + state + ".patch.yaml", state, type);
    addIfPresent(
        patches,
        LayerKind.STATE_TYPE,
        root + "state_type-" + state + "-" + type + ".patch.yaml",
        state,
        type);

    return new LayerSet(baseLayer, patches);
  }

  private void addIfPresent(
      List<LayerSet.Patch> patches, LayerKind expected, String path, String state, String type) {
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
}
