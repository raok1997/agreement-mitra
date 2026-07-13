package in.agreementmitra.documents.template;

/**
 * Provenance handle for a single contributing layer: its {@link LayerKind}, the {@link Dimensions}
 * it applies at, its {@code version}, and the resource path it was loaded from. {@link #id()} is
 * the stable, human-readable key the effective template records in its {@code {layerId -> version}}
 * provenance map so an agreement can pin exactly which layers (and versions) composed it.
 */
record LayerRef(LayerKind kind, Dimensions dimensions, int version, String resourcePath) {

  /**
   * A stable id for this layer, derived from its kind and the dimension(s) that identify it: {@code
   * base}, {@code type:<type>}, {@code state:<state>}, {@code state_type:<state>:<type>}, or {@code
   * language}. Two layers of the same kind at different dimensions get different ids.
   */
  String id() {
    return switch (kind) {
      case BASE -> "base";
      case TYPE -> "type:" + dimensions.type();
      case STATE -> "state:" + dimensions.state();
      case STATE_TYPE -> "state_type:" + dimensions.state() + ":" + dimensions.type();
      case LANGUAGE -> "language";
    };
  }
}
