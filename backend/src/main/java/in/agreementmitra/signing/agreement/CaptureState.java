package in.agreementmitra.signing.agreement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agreement's <b>capture state</b>: the flat working-set field map ({@code fieldKey -> value})
 * that produced its document, plus the list of added optional-section titles. Persisted as a single
 * {@code jsonb} column on {@link Agreement} (mirroring how {@code template_layer_versions} is
 * stored) so a saved agreement round-trips its complete content and the stored/signed draft renders
 * exactly what the live preview showed (preview/draft parity).
 *
 * <p>Held as <b>plain JDK types only</b> (a {@code String} map + a {@code String} list): the {@code
 * signing} aggregate carries no {@code documents}-module type, so the Modulith boundary stays
 * clean. It is <b>user content</b> -- validated at render by the {@code documents} projection
 * against the effective template's field schema, never trusted blindly here. Immutable: both
 * collections are defensively copied and made unmodifiable at construction. The copies tolerate a
 * {@code null} value (a submitted JSON field may be null), which {@code Map.copyOf} would reject.
 *
 * <p>Package-private -- an internal aggregate value object. Jackson (via Hibernate's JSON mapping)
 * builds it through the canonical record constructor on load.
 */
record CaptureState(Map<String, String> data, List<String> activeSections) {

  CaptureState {
    data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    activeSections =
        activeSections == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(activeSections));
  }
}
