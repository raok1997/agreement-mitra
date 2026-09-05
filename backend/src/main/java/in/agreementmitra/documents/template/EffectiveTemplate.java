package in.agreementmitra.documents.template;

import java.util.Map;

/**
 * The materialized result of resolving a {@code (state, type)} through its layers: a
 * fully-composed, re-validated {@link TemplateDefinition} together with the identity a future
 * agreement pins.
 *
 * <p>Identity is the triple {@code (dimensions, provenance, contentHash)}:
 *
 * <ul>
 *   <li>{@code dimensions} -- the selection coordinates resolution was asked for;
 *   <li>{@code provenance} -- the ordered {@code layerId -> version} map of every contributing
 *       layer, so the exact layer set (and versions) is recorded;
 *   <li>{@code contentHash} -- the SHA-256 of the composed template's canonical JSON, computed by
 *       the <b>same</b> {@link CanonicalJson} used for definitions, so a trivially-resolved base
 *       hashes identically to its own definition.
 * </ul>
 *
 * <p>Given the same dimensions and unchanged layer versions, resolution yields the same {@code
 * contentHash} on every run -- the anchor that lets a signed agreement be pinned and never silently
 * re-resolved against newer layers.
 */
record EffectiveTemplate(
    TemplateDefinition template,
    Dimensions dimensions,
    Map<String, Integer> provenance,
    String contentHash) {

  EffectiveTemplate {
    provenance = Map.copyOf(provenance);
  }
}
