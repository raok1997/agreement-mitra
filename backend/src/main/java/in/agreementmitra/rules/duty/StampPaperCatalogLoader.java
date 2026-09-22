package in.agreementmitra.rules.duty;

import com.fasterxml.jackson.databind.JsonNode;
import in.agreementmitra.rules.StampOffer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.core.io.Resource;

/**
 * Loads and validates stamp paper catalogs from their own directory (never the duty rule glob).
 * Defects throw {@link RuleSetDefinitionException}, stopping startup.
 */
final class StampPaperCatalogLoader {

  /**
   * Largest multiset enumeration a DENOMINATIONS medium may require of the planner, so a data typo
   * cannot turn a quote into a CPU spike.
   */
  static final long MAX_COMBINATIONS = 100_000;

  private static final Set<String> CATALOG_KEYS =
      Set.of("state", "effectiveFrom", "effectiveTo", "source", "media", "offer");
  private static final Set<String> MEDIUM_KEYS =
      Set.of("id", "kind", "denominations", "maxPapers", "minimumAmount");

  List<StampPaperCatalog> load(List<Resource> resources) {
    List<StampPaperCatalog> catalogs = new ArrayList<>();
    for (Resource resource : resources) {
      catalogs.add(build(YamlFields.describe(resource), YamlFields.read(resource)));
    }
    checkWindows(catalogs);
    return List.copyOf(catalogs);
  }

  private static StampPaperCatalog build(String sourceName, JsonNode root) {
    String state =
        root.path("state").isTextual()
            ? root.get("state").asText().trim().toUpperCase(Locale.ROOT)
            : "<no state>";
    YamlFields f = new YamlFields("stamp paper catalog " + state, sourceName);
    f.allowOnly(root, "catalog", CATALOG_KEYS);
    f.requiredText(root, "state");
    if ("IN".equals(state)) {
      throw f.defect("state IN is not a duty jurisdiction");
    }
    LocalDate from = f.date(root, "effectiveFrom");
    if (from == null) {
      throw f.defect("effectiveFrom is required");
    }
    LocalDate to = f.date(root, "effectiveTo");
    if (to != null && to.isBefore(from)) {
      throw f.defect("effectiveTo is before effectiveFrom");
    }
    String source = f.requiredText(root, "source");

    JsonNode mediaNode = root.get("media");
    if (mediaNode == null || !mediaNode.isArray() || mediaNode.isEmpty()) {
      throw f.defect("declares no media");
    }
    List<StampPaperCatalog.Medium> media = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (JsonNode m : mediaNode) {
      f.allowOnly(m, "medium", MEDIUM_KEYS);
      String id = f.requiredText(m, "id");
      if (!ids.add(id)) {
        throw f.defect("duplicate medium id " + id);
      }
      StampPaperCatalog.MediumKind kind =
          f.enumValue(m, "kind", StampPaperCatalog.MediumKind.class);
      if (kind == null) {
        throw f.defect("medium " + id + " kind is required");
      }
      media.add(
          kind == StampPaperCatalog.MediumKind.DENOMINATIONS
              ? denominations(f, m, id)
              : anyAmount(f, m, id));
    }
    StampOffer offer = offer(f, root.get("offer"), media);
    return new StampPaperCatalog(
        state,
        sourceName,
        from,
        to,
        source,
        media,
        offer,
        hash(state, from, to, source, media, offer));
  }

  /**
   * The optional offer policy. {@code SINGLE_PAPERS} must name denominations that some
   * DENOMINATIONS medium issues, and a pre-selected value among them.
   */
  private static StampOffer offer(
      YamlFields f, JsonNode node, List<StampPaperCatalog.Medium> media) {
    if (node == null || node.isNull()) {
      return StampOffer.PLANNED;
    }
    f.allowOnly(node, "offer", Set.of("mode", "denominations", "preselect"));
    StampOffer.Mode mode = f.enumValue(node, "mode", StampOffer.Mode.class);
    if (mode == null) {
      throw f.defect("offer mode is required");
    }
    if (mode == StampOffer.Mode.PLANNED) {
      if (node.has("denominations") || node.has("preselect")) {
        throw f.defect("offer mode PLANNED takes no denominations or preselect");
      }
      return StampOffer.PLANNED;
    }
    Set<Long> issued = new HashSet<>();
    media.forEach(m -> issued.addAll(m.denominationsPaise()));
    JsonNode values = node.get("denominations");
    if (values == null || !values.isArray() || values.isEmpty()) {
      throw f.defect("offer SINGLE_PAPERS declares no denominations");
    }
    List<Long> offered = new ArrayList<>();
    for (JsonNode v : values) {
      if (!v.isTextual()) {
        throw f.defect("offer denominations must be quoted decimal strings");
      }
      long paise = toPaise(f, new BigDecimal(v.asText().trim()), "offer denomination");
      if (!issued.contains(paise)) {
        throw f.defect("offer denomination " + v.asText() + " is not issued by any medium");
      }
      if (!offered.contains(paise)) {
        offered.add(paise);
      }
    }
    offered.sort(Comparator.reverseOrder());
    BigDecimal preselect = f.decimal(node, "preselect");
    if (preselect == null) {
      throw f.defect("offer SINGLE_PAPERS requires preselect");
    }
    long preselectPaise = toPaise(f, preselect, "offer preselect");
    if (!offered.contains(preselectPaise)) {
      throw f.defect(
          "offer preselect " + preselect.toPlainString() + " is not an offered denomination");
    }
    return new StampOffer(mode, offered, preselectPaise);
  }

  private static StampPaperCatalog.Medium denominations(YamlFields f, JsonNode m, String id) {
    if (m.has("minimumAmount")) {
      throw f.defect("medium " + id + " is DENOMINATIONS and must not set minimumAmount");
    }
    JsonNode values = m.get("denominations");
    if (values == null || !values.isArray() || values.isEmpty()) {
      throw f.defect("medium " + id + " declares no denominations");
    }
    Set<Long> distinct = new HashSet<>();
    for (JsonNode v : values) {
      if (!v.isTextual()) {
        throw f.defect("medium " + id + " denominations must be quoted decimal strings");
      }
      long paise = toPaise(f, new BigDecimal(v.asText().trim()), "medium " + id + " denomination");
      if (paise <= 0) {
        throw f.defect("medium " + id + " has a non-positive denomination " + v.asText());
      }
      if (!distinct.add(paise)) {
        throw f.defect("medium " + id + " repeats denomination " + v.asText());
      }
    }
    Integer maxPapers = f.integer(m, "maxPapers");
    if (maxPapers == null || maxPapers < 1) {
      throw f.defect("medium " + id + " maxPapers must be a positive integer");
    }
    long combinations = combinations(distinct.size(), maxPapers);
    if (combinations > MAX_COMBINATIONS) {
      throw f.defect(
          "medium "
              + id
              + " needs "
              + combinations
              + " combinations to plan, over "
              + MAX_COMBINATIONS);
    }
    List<Long> sorted = new ArrayList<>(distinct);
    sorted.sort(Comparator.reverseOrder());
    return new StampPaperCatalog.Medium(
        id, StampPaperCatalog.MediumKind.DENOMINATIONS, sorted, maxPapers, 0);
  }

  private static StampPaperCatalog.Medium anyAmount(YamlFields f, JsonNode m, String id) {
    if (m.has("denominations") || m.has("maxPapers")) {
      throw f.defect("medium " + id + " is ANY_AMOUNT and must not set denominations or maxPapers");
    }
    BigDecimal minimum = f.decimal(m, "minimumAmount");
    if (minimum == null || minimum.signum() < 0) {
      throw f.defect("medium " + id + " minimumAmount is required and must not be negative");
    }
    return new StampPaperCatalog.Medium(
        id,
        StampPaperCatalog.MediumKind.ANY_AMOUNT,
        List.of(),
        1,
        toPaise(f, minimum, "medium " + id + " minimumAmount"));
  }

  private static long toPaise(YamlFields f, BigDecimal rupees, String what) {
    try {
      return rupees.movePointRight(2).longValueExact();
    } catch (ArithmeticException e) {
      throw f.defect(what + " must be a whole number of paise: " + rupees.toPlainString());
    }
  }

  /** Sum over k = 1..maxPapers of multisets of size k from n values: C(n + k - 1, k). */
  static long combinations(int n, int maxPapers) {
    long total = 0;
    for (int k = 1; k <= maxPapers; k++) {
      long c = 1;
      for (int i = 1; i <= k; i++) {
        c = c * (n + i - 1) / i;
        if (c > MAX_COMBINATIONS) {
          return MAX_COMBINATIONS + 1;
        }
      }
      total += c;
      if (total > MAX_COMBINATIONS) {
        return MAX_COMBINATIONS + 1;
      }
    }
    return total;
  }

  private static String hash(
      String state,
      LocalDate from,
      LocalDate to,
      String source,
      List<StampPaperCatalog.Medium> media,
      StampOffer offer) {
    Map<String, Object> content = new TreeMap<>();
    Map<String, Object> o = new TreeMap<>();
    o.put("mode", offer.mode().name());
    o.put("denominationsPaise", offer.denominationsPaise());
    o.put("preselectPaise", offer.preselectPaise());
    content.put("offer", o);
    content.put("state", state);
    content.put("effectiveFrom", from.toString());
    content.put("effectiveTo", to == null ? null : to.toString());
    content.put("source", source);
    List<Object> ms = new ArrayList<>();
    for (StampPaperCatalog.Medium medium : media) {
      Map<String, Object> m = new TreeMap<>();
      m.put("id", medium.id());
      m.put("kind", medium.kind().name());
      m.put("denominationsPaise", medium.denominationsPaise());
      m.put("maxPapers", medium.maxPapers());
      m.put("minimumPaise", medium.minimumPaise());
      ms.add(m);
    }
    content.put("media", ms);
    return CanonicalHash.of(content);
  }

  private static void checkWindows(List<StampPaperCatalog> catalogs) {
    Map<String, List<StampPaperCatalog>> byState = new TreeMap<>();
    catalogs.forEach(c -> byState.computeIfAbsent(c.state(), k -> new ArrayList<>()).add(c));
    for (List<StampPaperCatalog> list : byState.values()) {
      List<StampPaperCatalog> sorted = new ArrayList<>(list);
      sorted.sort(Comparator.comparing(StampPaperCatalog::effectiveFrom));
      for (int i = 1; i < sorted.size(); i++) {
        StampPaperCatalog prev = sorted.get(i - 1);
        StampPaperCatalog next = sorted.get(i);
        if (prev.effectiveTo() == null || !next.effectiveFrom().isAfter(prev.effectiveTo())) {
          throw new RuleSetDefinitionException(
              "stamp paper catalog " + next.state(),
              next.sourceName(),
              "effective window overlaps " + prev.sourceName());
        }
      }
    }
  }
}
