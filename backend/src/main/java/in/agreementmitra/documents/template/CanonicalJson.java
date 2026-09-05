package in.agreementmitra.documents.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Serializes a loaded definition's content to a deterministic <b>canonical JSON</b> and hashes it.
 *
 * <p><b>Canonicalization rules</b> (a hash is only as stable as these -- the layered-resolver CR
 * MUST reuse this exact canonicalizer when it hashes effective templates):
 *
 * <ul>
 *   <li>The canonical form is built from the <b>loaded model</b>, not the source YAML, so it is a
 *       pure function of the definition's <i>content</i>: two byte-different-but-equivalent YAML
 *       files (differing only in key order, whitespace, comments, or explicit-vs-defaulted optional
 *       fields) produce identical canonical JSON.
 *   <li>Object keys are sorted lexicographically -- top-level via a {@link TreeMap}, nested
 *       POJO/record properties via {@link MapperFeature#SORT_PROPERTIES_ALPHABETICALLY} and map
 *       entries via {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}.
 *   <li>Array order is <b>preserved</b> -- fields, clauses, sections, options, and slots are
 *       content-bearing sequences, never sorted.
 *   <li>All properties are always emitted (Jackson's default {@code Include.ALWAYS}); an absent
 *       optional is normalized by the model to {@code null} and serialized as {@code null}, so
 *       presence/absence in the YAML cannot change the hash.
 *   <li>Scalars use Jackson's default textual form; {@code contentHash} is excluded (it is the hash
 *       of everything else).
 * </ul>
 *
 * <p>The content hash is SHA-256 over the UTF-8 bytes of the canonical JSON, lower-case hex
 * encoded.
 */
final class CanonicalJson {

  private static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          // SORT_PROPERTIES_ALPHABETICALLY + ORDER_MAP_ENTRIES_BY_KEYS give a stable key order.
          // Jackson's default inclusion is ALWAYS (nulls emitted), which is exactly what we want so
          // an absent optional and an explicit null hash identically -- so it is not set here.
          .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
          .build();

  private CanonicalJson() {}

  /** The deterministic canonical JSON of a definition's content (excludes {@code contentHash}). */
  static String canonicalize(
      Meta meta, List<Field> fields, List<Clause> clauses, List<Section> sections) {
    TreeMap<String, Object> content = new TreeMap<>();
    content.put("meta", meta);
    content.put("fields", fields);
    content.put("clauses", clauses);
    content.put("sections", sections);
    try {
      return CANONICAL.writeValueAsString(content);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to canonicalize template definition", e);
    }
  }

  /** SHA-256 of the canonical JSON's UTF-8 bytes, lower-case hex. */
  static String contentHash(String canonicalJson) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] digest = sha256.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
