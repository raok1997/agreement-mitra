package in.agreementmitra.documents.template;

import java.util.List;

/**
 * An immutable, structurally-validated layer patch: {@code meta { kind, dimensions, version }} and
 * an ordered list of {@link Op operations} applied, in author order, over the composed-so-far
 * template. Constructed only by {@link LayerPatchLoader} once JSON-Schema structural validation has
 * passed. A patch is <b>not</b> a full definition; a {@code base} layer is a {@link
 * TemplateDefinition}, never a patch.
 */
record LayerPatch(PatchMeta meta, List<Op> ops) {

  LayerPatch {
    ops = List.copyOf(ops);
  }

  /**
   * Selection + versioning metadata of a patch. {@code dimensions} carries only the dimension(s)
   * relevant to the {@code kind} (a type patch's {@code state} may be absent, and vice versa).
   */
  record PatchMeta(LayerKind kind, Dimensions dimensions, int version) {}
}
