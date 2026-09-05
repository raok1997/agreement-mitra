package in.agreementmitra.documents.api;

import java.util.Map;

/**
 * The identity of the effective template a document was generated from: the source {@code
 * templateId}, the composed template's {@code contentHash}, and the {@code layerVersions} map
 * ({@code layerId -> version}) of every contributing layer. Returned on a generate projection so a
 * caller can pin exactly what was rendered.
 *
 * <p>Not yet consumed in this change (generate-as-draft renders but does not pin); CR-3 ({@code
 * agreement-template-pin}) records it on the agreement so a signed document can never be silently
 * re-resolved against newer layers. System-owned integrity metadata -- no user data.
 */
public record EffectiveTemplateIdentity(
    String templateId, String contentHash, Map<String, Integer> layerVersions) {

  public EffectiveTemplateIdentity {
    layerVersions = Map.copyOf(layerVersions);
  }
}
