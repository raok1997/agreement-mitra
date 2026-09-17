package in.agreementmitra.rules.duty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical JSON + SHA-256 for rule and catalog content. Callers build the content from the
 * <b>loaded model</b> as nested maps and lists; map keys are sorted, list order is preserved, and
 * decimals are normalized with {@link #decimal} so {@code "0.40"} and {@code "0.4"} hash the same.
 *
 * <p>A deliberate small duplicate of {@code documents}' canonicalizer, which is internal to that
 * module (design D6).
 */
final class CanonicalHash {

  private static final ObjectMapper CANONICAL =
      JsonMapper.builder().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build();

  private CanonicalHash() {}

  static String decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  static String of(Object content) {
    try {
      byte[] json = CANONICAL.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to canonicalize rule content", e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
