package in.agreementmitra.rules.duty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads stamp duty rule files, merges each onto the abstract {@code base}, validates, and hashes
 * (design D3, D6, D7). Any defect throws {@link RuleSetDefinitionException}, which stops the
 * application context.
 *
 * <p>Merge: a state rule inherits {@code rounding}, {@code registration}, {@code counterpartDuty}
 * and {@code surcharges} from {@code base} unless it declares them. Slabs, params and cases are
 * never inherited. One level only, and {@code base} is the only valid target.
 */
final class RuleSetLoader {

  static final String BASE_ID = "base";

  private static final String NATIONAL = "IN";

  private static final Set<String> INHERITABLE =
      Set.of("rounding", "registration", "counterpartDuty", "surcharges");
  private static final Set<String> ABSTRACT_KEYS = union(Set.of("abstract", "id"), INHERITABLE);
  private static final Set<String> RULE_KEYS =
      union(
          Set.of(
              "id",
              "extends",
              "state",
              "instrumentKind",
              "usage",
              "effectiveFrom",
              "effectiveTo",
              "legalReference",
              "counselReview",
              "extension",
              "params",
              "slabs",
              "minimumAmount",
              "maximumAmount",
              "cases"),
          INHERITABLE);
  private static final Set<String> SLAB_KEYS =
      Set.of("minMonths", "maxMonths", "consideration", "ratePercent", "fixedAmount");
  private static final Set<String> CASE_KEYS = Set.of("name", "basis", "expect");
  private static final Set<String> BASIS_KEYS =
      Set.of(
          "dutyState",
          "executionDate",
          "termMonths",
          "monthlyRent",
          "escalationPercent",
          "escalationEveryMonths",
          "rentFreeMonths",
          "refundableDeposit",
          "nonRefundableDeposit",
          "advanceRent",
          "premium",
          "counterparts");
  private static final Set<String> EXPECT_KEYS = Set.of("outcome", "amountPaise", "plans");

  private static final ObjectMapper PLAIN = new ObjectMapper();

  /** Extension id to the quantity names it provides. Every id must be referenced by a rule. */
  private final Map<String, Set<String>> extensionQuantities;

  RuleSetLoader(Map<String, Set<String>> extensionQuantities) {
    this.extensionQuantities = Map.copyOf(extensionQuantities);
  }

  static List<Resource> resolve(List<String> patterns) {
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    List<Resource> resources = new ArrayList<>();
    for (String pattern : patterns) {
      try {
        for (Resource resource : resolver.getResources(pattern.trim())) {
          resources.add(resource);
        }
      } catch (IOException e) {
        throw new RuleSetDefinitionException(
            "location", pattern, "unresolvable: " + e.getMessage());
      }
    }
    return resources;
  }

  /** Loads, merges and validates every rule; returns the selectable (non-abstract) rules. */
  List<RuleSet> load(List<Resource> resources) {
    JsonNode base = null;
    List<Map.Entry<String, JsonNode>> concrete = new ArrayList<>();
    for (Resource resource : resources) {
      String source = YamlFields.describe(resource);
      JsonNode root = YamlFields.read(resource);
      if (root.path("abstract").asBoolean(false)) {
        YamlFields fields = new YamlFields("abstract rule", source);
        fields.allowOnly(root, "abstract rule", ABSTRACT_KEYS);
        if (!BASE_ID.equals(fields.requiredText(root, "id"))) {
          throw fields.defect("the only abstract rule is '" + BASE_ID + "'");
        }
        if (base != null) {
          throw fields.defect("duplicate abstract rule '" + BASE_ID + "'");
        }
        base = root;
      } else {
        concrete.add(Map.entry(source, root));
      }
    }

    List<RuleSet> rules = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Map.Entry<String, JsonNode> entry : concrete) {
      RuleSet rule = build(entry.getKey(), entry.getValue(), base);
      if (!ids.add(rule.id())) {
        throw new RuleSetDefinitionException(
            "rule " + rule.id(), rule.sourceName(), "duplicate id");
      }
      rules.add(rule);
    }
    checkWindows(rules);
    checkExtensionsReferenced(rules);
    return List.copyOf(rules);
  }

  private RuleSet build(String source, JsonNode root, JsonNode base) {
    String id = root.path("id").isTextual() ? root.get("id").asText() : "<no id>";
    YamlFields f = new YamlFields("rule " + id, source);
    f.allowOnly(root, "rule", RULE_KEYS);
    f.requiredText(root, "id");
    if (!BASE_ID.equals(f.text(root, "extends"))) {
      throw f.defect("must declare 'extends: " + BASE_ID + "' (the only valid target)");
    }
    if (base == null) {
      throw f.defect("abstract rule '" + BASE_ID + "' was not loaded");
    }
    JsonNode merged = merge(base, root);

    String state = f.requiredText(merged, "state").toUpperCase(java.util.Locale.ROOT);
    if (NATIONAL.equals(state)) {
      throw f.defect("state IN is not a duty jurisdiction: there is no national stamp duty rate");
    }
    InstrumentKind kind =
        required(f, f.enumValue(merged, "instrumentKind", InstrumentKind.class), "instrumentKind");
    Usage usage = required(f, f.enumValue(merged, "usage", Usage.class), "usage");
    LocalDate from = required(f, f.date(merged, "effectiveFrom"), "effectiveFrom");
    LocalDate to = f.date(merged, "effectiveTo");
    if (to != null && to.isBefore(from)) {
      throw f.defect("effectiveTo is before effectiveFrom");
    }
    String legalReference = f.requiredText(merged, "legalReference");
    String extension = f.text(merged, "extension");
    if (extension != null && !extensionQuantities.containsKey(extension)) {
      throw f.defect("extension '" + extension + "' is not a registered DutyExtension");
    }

    Map<String, BigDecimal> params = new TreeMap<>();
    JsonNode paramsNode = merged.path("params");
    if (!paramsNode.isMissingNode() && !paramsNode.isNull()) {
      if (!paramsNode.isObject()) {
        throw f.defect("params must be a mapping");
      }
      paramsNode
          .fieldNames()
          .forEachRemaining(name -> params.put(name, f.decimal(paramsNode, name)));
    }

    Set<String> available = new HashSet<>(Quantities.STANDARD_NAMES);
    if (extension != null) {
      available.addAll(extensionQuantities.get(extension));
    }
    List<RuleSet.Slab> slabs = slabs(f, merged, available, params);

    BigDecimal minimum = f.nonNegativeDecimal(merged, "minimumAmount");
    BigDecimal maximum = f.nonNegativeDecimal(merged, "maximumAmount");
    if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
      throw f.defect("minimumAmount exceeds maximumAmount");
    }

    RuleSet unhashed =
        new RuleSet(
            f.requiredText(root, "id"),
            source,
            state,
            kind,
            usage,
            from,
            to,
            legalReference,
            extension,
            params,
            slabs,
            new RuleSet.Bounds(minimum, maximum),
            surcharges(f, merged),
            Objects.requireNonNullElse(
                f.nonNegativeDecimal(merged, "counterpartDuty"), BigDecimal.ZERO),
            rounding(f, merged),
            registration(f, merged),
            counselReview(merged),
            List.of(),
            null);
    List<RuleSet.RuleCase> cases = cases(f, merged, unhashed);
    return new RuleSet(
        unhashed.id(),
        source,
        state,
        kind,
        usage,
        from,
        to,
        legalReference,
        extension,
        params,
        slabs,
        unhashed.bounds(),
        unhashed.surcharges(),
        unhashed.counterpartDuty(),
        unhashed.rounding(),
        unhashed.registration(),
        unhashed.counselReview(),
        cases,
        RuleHasher.hash(unhashed));
  }

  private static JsonNode merge(JsonNode base, JsonNode rule) {
    var merged = rule.deepCopy();
    for (String key : INHERITABLE) {
      if (!rule.has(key) && base.has(key)) {
        ((com.fasterxml.jackson.databind.node.ObjectNode) merged)
            .set(key, base.get(key).deepCopy());
      }
    }
    return merged;
  }

  private static List<RuleSet.Slab> slabs(
      YamlFields f, JsonNode merged, Set<String> available, Map<String, BigDecimal> params) {
    JsonNode node = merged.get("slabs");
    if (node == null || !node.isArray() || node.isEmpty()) {
      throw f.defect("a non-abstract rule must declare at least one slab");
    }
    List<RuleSet.Slab> slabs = new ArrayList<>();
    for (JsonNode s : node) {
      f.allowOnly(s, "slab", SLAB_KEYS);
      int min = required(f, f.integer(s, "minMonths"), "slab minMonths");
      int max = required(f, f.integer(s, "maxMonths"), "slab maxMonths");
      if (min < 1 || max < min) {
        throw f.defect("slab " + min + "-" + max + " months is not a valid range");
      }
      BigDecimal rate = f.nonNegativeDecimal(s, "ratePercent");
      BigDecimal fixed = f.nonNegativeDecimal(s, "fixedAmount");
      if ((rate == null) == (fixed == null)) {
        throw f.defect(
            "slab " + min + "-" + max + " must set exactly one of ratePercent and fixedAmount");
      }
      List<String> consideration = new ArrayList<>();
      JsonNode names = s.get("consideration");
      if (names != null && !names.isNull()) {
        if (!names.isArray()) {
          throw f.defect("slab " + min + "-" + max + " consideration must be a list");
        }
        names.forEach(n -> consideration.add(n.asText()));
      }
      if (rate != null && consideration.isEmpty()) {
        throw f.defect("slab " + min + "-" + max + " has a rate but no consideration");
      }
      if (fixed != null && !consideration.isEmpty()) {
        throw f.defect("slab " + min + "-" + max + " has a fixedAmount and a consideration");
      }
      for (String name : consideration) {
        if (!available.contains(name)) {
          throw f.defect("slab " + min + "-" + max + " references unknown quantity " + name);
        }
        if (Quantities.DEPOSIT_NOTIONAL_INTEREST.equals(name)
            && !params.containsKey(Quantities.DEPOSIT_INTEREST_RATE_PARAM)) {
          throw f.defect(name + " requires params." + Quantities.DEPOSIT_INTEREST_RATE_PARAM);
        }
      }
      slabs.add(new RuleSet.Slab(min, max, consideration, rate, fixed));
    }
    slabs.sort(Comparator.comparingInt(RuleSet.Slab::minMonths));
    for (int i = 1; i < slabs.size(); i++) {
      RuleSet.Slab prev = slabs.get(i - 1);
      RuleSet.Slab next = slabs.get(i);
      if (next.minMonths() <= prev.maxMonths()) {
        throw f.defect(
            "slabs overlap at "
                + next.minMonths()
                + " months ("
                + prev.minMonths()
                + "-"
                + prev.maxMonths()
                + " and "
                + next.minMonths()
                + "-"
                + next.maxMonths()
                + ")");
      }
      if (next.minMonths() > prev.maxMonths() + 1) {
        int gapFrom = prev.maxMonths() + 1;
        int gapTo = next.minMonths() - 1;
        throw f.defect(
            "slab gap at " + (gapFrom == gapTo ? gapFrom : gapFrom + "-" + gapTo) + " months");
      }
    }
    return slabs;
  }

  private static List<RuleSet.Surcharge> surcharges(YamlFields f, JsonNode merged) {
    JsonNode node = merged.get("surcharges");
    List<RuleSet.Surcharge> surcharges = new ArrayList<>();
    if (node == null || node.isNull()) {
      return surcharges;
    }
    if (!node.isArray()) {
      throw f.defect("surcharges must be a list");
    }
    for (JsonNode s : node) {
      f.allowOnly(s, "surcharge", Set.of("name", "percentOfDuty"));
      surcharges.add(
          new RuleSet.Surcharge(
              f.requiredText(s, "name"),
              required(f, f.nonNegativeDecimal(s, "percentOfDuty"), "surcharge percentOfDuty")));
    }
    return surcharges;
  }

  private static RuleSet.Rounding rounding(YamlFields f, JsonNode merged) {
    JsonNode node = merged.get("rounding");
    if (node == null || !node.isObject()) {
      throw f.defect("rounding is required (declare it in the rule or in base)");
    }
    f.allowOnly(node, "rounding", Set.of("mode", "unitRupees"));
    RuleSet.Rounding.Mode mode =
        required(f, f.enumValue(node, "mode", RuleSet.Rounding.Mode.class), "rounding mode");
    int unit = required(f, f.integer(node, "unitRupees"), "rounding unitRupees");
    if (unit < 1) {
      throw f.defect("rounding unitRupees must be positive");
    }
    return new RuleSet.Rounding(mode, unit);
  }

  private static RuleSet.RegistrationRule registration(YamlFields f, JsonNode merged) {
    JsonNode node = merged.get("registration");
    if (node == null || node.isNull()) {
      return new RuleSet.RegistrationRule(null);
    }
    f.allowOnly(node, "registration", Set.of("requiredWhenTermMonthsOver"));
    return new RuleSet.RegistrationRule(f.integer(node, "requiredWhenTermMonthsOver"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> counselReview(JsonNode merged) {
    JsonNode node = merged.get("counselReview");
    if (node == null || node.isNull()) {
      return null;
    }
    return PLAIN.convertValue(node, LinkedHashMap.class);
  }

  private static List<RuleSet.RuleCase> cases(YamlFields f, JsonNode merged, RuleSet rule) {
    JsonNode node = merged.get("cases");
    List<RuleSet.RuleCase> cases = new ArrayList<>();
    if (node == null || node.isNull()) {
      return cases;
    }
    if (!node.isArray()) {
      throw f.defect("cases must be a list");
    }
    for (JsonNode c : node) {
      f.allowOnly(c, "case", CASE_KEYS);
      String name = f.requiredText(c, "name");
      YamlFields cf = f.as("rule " + rule.id() + " case '" + name + "'");
      JsonNode b = c.path("basis");
      if (!b.isObject()) {
        throw cf.defect("basis is required");
      }
      cf.allowOnly(b, "basis", BASIS_KEYS);
      DutyBasis basis;
      try {
        DutyBasis.Builder builder =
            DutyBasis.builder(
                Objects.requireNonNullElse(cf.text(b, "dutyState"), rule.state()),
                rule.instrumentKind(),
                rule.usage(),
                Objects.requireNonNullElse(cf.date(b, "executionDate"), rule.effectiveFrom()),
                required(cf, cf.integer(b, "termMonths"), "termMonths"),
                cf.decimal(b, "monthlyRent"));
        builder
            .escalation(
                cf.decimal(b, "escalationPercent"),
                Objects.requireNonNullElse(cf.integer(b, "escalationEveryMonths"), 0))
            .rentFreeMonths(Objects.requireNonNullElse(cf.integer(b, "rentFreeMonths"), 0))
            .refundableDeposit(cf.decimal(b, "refundableDeposit"))
            .nonRefundableDeposit(cf.decimal(b, "nonRefundableDeposit"))
            .advanceRent(cf.decimal(b, "advanceRent"))
            .premium(cf.decimal(b, "premium"))
            .counterparts(Objects.requireNonNullElse(cf.integer(b, "counterparts"), 1));
        basis = builder.build();
      } catch (IllegalArgumentException e) {
        throw cf.defect(e.getMessage());
      }
      JsonNode e = c.path("expect");
      if (!e.isObject()) {
        throw cf.defect("expect is required");
      }
      cf.allowOnly(e, "expect", EXPECT_KEYS);
      RuleSet.OutcomeType outcome =
          required(cf, cf.enumValue(e, "outcome", RuleSet.OutcomeType.class), "expect outcome");
      Long amount = cf.longValue(e, "amountPaise");
      if ((outcome == RuleSet.OutcomeType.QUOTED) != (amount != null)) {
        throw cf.defect("expect amountPaise is required for QUOTED and only for QUOTED");
      }
      Map<String, Long> plans = new LinkedHashMap<>();
      JsonNode p = e.get("plans");
      if (p != null && !p.isNull()) {
        if (!p.isObject()) {
          throw cf.defect("expect plans must be a mapping of medium id to total paise");
        }
        p.fieldNames()
            .forEachRemaining(
                medium -> {
                  JsonNode v = p.get(medium);
                  if (v.isTextual() && "UNPLANNABLE".equals(v.asText())) {
                    plans.put(medium, null);
                  } else if (v.isIntegralNumber()) {
                    plans.put(medium, v.longValue());
                  } else {
                    throw cf.defect("plan " + medium + " must be total paise or UNPLANNABLE");
                  }
                });
      }
      cases.add(new RuleSet.RuleCase(name, basis, new RuleSet.Expectation(outcome, amount, plans)));
    }
    return cases;
  }

  private static void checkWindows(List<RuleSet> rules) {
    Map<String, List<RuleSet>> byKey = new TreeMap<>();
    for (RuleSet rule : rules) {
      byKey
          .computeIfAbsent(
              rule.state() + "/" + rule.instrumentKind() + "/" + rule.usage(),
              k -> new ArrayList<>())
          .add(rule);
    }
    for (Map.Entry<String, List<RuleSet>> entry : byKey.entrySet()) {
      List<RuleSet> sorted = new ArrayList<>(entry.getValue());
      sorted.sort(Comparator.comparing(RuleSet::effectiveFrom));
      for (int i = 1; i < sorted.size(); i++) {
        RuleSet prev = sorted.get(i - 1);
        RuleSet next = sorted.get(i);
        if (prev.effectiveTo() == null || !next.effectiveFrom().isAfter(prev.effectiveTo())) {
          throw new RuleSetDefinitionException(
              "rule " + next.id(),
              next.sourceName(),
              "effective window overlaps rule " + prev.id() + " for " + entry.getKey());
        }
      }
    }
  }

  private void checkExtensionsReferenced(List<RuleSet> rules) {
    Set<String> unreferenced = new TreeSet<>(extensionQuantities.keySet());
    rules.stream().map(RuleSet::extension).filter(Objects::nonNull).forEach(unreferenced::remove);
    if (!unreferenced.isEmpty()) {
      throw new RuleSetDefinitionException(
          "extension(s) " + unreferenced, "DutyExtension beans", "not referenced by any rule");
    }
  }

  private static <T> T required(YamlFields f, T value, String field) {
    if (value == null) {
      throw f.defect(field + " is required");
    }
    return value;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> all = new HashSet<>(a);
    all.addAll(b);
    return Set.copyOf(all);
  }
}
