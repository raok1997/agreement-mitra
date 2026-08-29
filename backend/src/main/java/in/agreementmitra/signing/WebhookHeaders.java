package in.agreementmitra.signing;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The inbound webhook's transport headers, in a vendor-neutral, case-insensitive form.
 *
 * <p>Exists because webhook authentication is provider-specific and one of the supported mechanisms
 * carries its credential in an HTTP <b>header</b> rather than the JSON body: ZOOP eSign v5 sends
 * {@code webhook-security-key}, whose value is issued per transaction at create time. The previous
 * contract was explicitly header-free (Leegality's MAC lives in the body), so it could not express
 * that. Passing a narrow value object rather than a servlet type keeps the {@code api} layer's
 * transport concerns out of the provider seam.
 *
 * <p>Header names are matched case-insensitively (HTTP header names are case-insensitive, and
 * containers differ on how they normalise them). Values are never logged - a header may carry a
 * credential.
 */
public final class WebhookHeaders {

  private static final WebhookHeaders EMPTY = new WebhookHeaders(Map.of());

  private final Map<String, String> byName;

  private WebhookHeaders(Map<String, String> byName) {
    this.byName = byName;
  }

  /** Wrap the raw header map; null-safe. Names are normalised for case-insensitive lookup. */
  public static WebhookHeaders of(Map<String, String> raw) {
    if (raw == null || raw.isEmpty()) {
      return EMPTY;
    }
    Map<String, String> normalised = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    raw.forEach(
        (name, value) -> {
          if (name != null && value != null) {
            normalised.putIfAbsent(name, value);
          }
        });
    return new WebhookHeaders(Collections.unmodifiableMap(normalised));
  }

  /** No headers at all - the safe default for a caller that has none to offer. */
  public static WebhookHeaders empty() {
    return EMPTY;
  }

  /** The value of {@code name}, matched case-insensitively; empty when absent or blank. */
  public Optional<String> value(String name) {
    if (name == null) {
      return Optional.empty();
    }
    String value = byName.get(name);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  /** Header names only - never the values (a value may be a credential). */
  @Override
  public String toString() {
    return "WebhookHeaders{names=" + byName.keySet() + "}";
  }
}
