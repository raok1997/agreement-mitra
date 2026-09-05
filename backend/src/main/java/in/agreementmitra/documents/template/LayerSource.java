package in.agreementmitra.documents.template;

import java.util.List;

/**
 * The seam that supplies, for a given {@code (state, type)}, the ordered layers that compose into
 * an effective template: the {@code base} definition plus the applicable patches in <b>precedence
 * order</b> ({@code type -> state -> state_type}; the reserved {@code language} kind is excluded).
 *
 * <p>Resolution depends only on this interface, so the classpath implementation used here swaps
 * cleanly for a database-backed registry (the catalog CR) without touching {@link
 * TemplateResolver}.
 */
interface LayerSource {

  /**
   * The ordered layers for {@code (state, type)}. Implementations MUST return the base first and
   * the patches in precedence order; a missing base is a hard failure ({@link
   * ResolutionException}).
   */
  LayerSet layersFor(String state, String type);

  /** The base definition (with its provenance) plus the ordered patches that apply over it. */
  record LayerSet(Base base, List<Patch> patches) {

    public LayerSet {
      patches = List.copyOf(patches);
    }

    /** The base layer: a full {@link TemplateDefinition} and its provenance {@link LayerRef}. */
    record Base(LayerRef ref, TemplateDefinition definition) {}

    /** A patch layer: a {@link LayerPatch} and its provenance {@link LayerRef}. */
    record Patch(LayerRef ref, LayerPatch patch) {}
  }
}
