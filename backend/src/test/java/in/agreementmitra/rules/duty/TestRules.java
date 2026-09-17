package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyBasis.InstrumentKind;
import in.agreementmitra.rules.DutyBasis.Usage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/** Shared helpers for stamp duty unit tests: inline YAML resources and quick bases. */
final class TestRules {

  static final Resource BASE = new ClassPathResource("rules/stamp-duty/base.yaml");

  static final List<String> DEFAULT_RULE_LOCATIONS =
      List.of("classpath*:rules/stamp-duty/**/*.yaml");
  static final List<String> DEFAULT_CATALOG_LOCATIONS =
      List.of("classpath*:rules/stamp-paper/*.yaml");

  /** A permissive single-medium catalog for engine tests that do not care about planning. */
  static final String ANY_AMOUNT_CATALOG =
      """
      state: ZZ
      effectiveFrom: "2000-01-01"
      source: "test"
      media:
        - { id: e-stamp, kind: ANY_AMOUNT, minimumAmount: "0" }
      """;

  private TestRules() {}

  static Resource yaml(String name, String text) {
    return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8), name) {
      @Override
      public String getFilename() {
        return name;
      }
    };
  }

  /** Loads the real base plus the given rule texts. */
  static List<RuleSet> rules(Map<String, Set<String>> extensionQuantities, String... ruleTexts) {
    List<Resource> resources = new ArrayList<>();
    resources.add(BASE);
    for (int i = 0; i < ruleTexts.length; i++) {
      resources.add(yaml("rule-" + i + ".yaml", ruleTexts[i]));
    }
    return new RuleSetLoader(extensionQuantities).load(resources);
  }

  static List<RuleSet> rules(String... ruleTexts) {
    return rules(Map.of(), ruleTexts);
  }

  static List<StampPaperCatalog> catalogs(String... texts) {
    List<Resource> resources = new ArrayList<>();
    for (int i = 0; i < texts.length; i++) {
      resources.add(yaml("catalog-" + i + ".yaml", texts[i]));
    }
    return new StampPaperCatalogLoader().load(resources);
  }

  static DutyEngine engine(List<DutyExtension> extensions, String catalog, String... ruleTexts) {
    Map<String, Set<String>> provided = new java.util.HashMap<>();
    extensions.forEach(e -> provided.put(e.id(), e.providedQuantities()));
    return new DutyEngine(
        new RuleSetRegistry(rules(provided, ruleTexts)),
        catalogs(catalog),
        extensions,
        new StampPaperPlanner(),
        false);
  }

  static DutyEngine engine(String... ruleTexts) {
    return engine(List.of(), ANY_AMOUNT_CATALOG, ruleTexts);
  }

  /** A single-slab ZZ residential lease rule; {@code extra} is appended YAML (top-level keys). */
  static String rule(String slabs, String extra) {
    return """
        extends: base
        id: ZZ-test
        state: ZZ
        instrumentKind: LEASE
        usage: RESIDENTIAL
        effectiveFrom: "2024-01-01"
        legalReference: "test"
        slabs:
        %s
        %s
        """
        .formatted(slabs.indent(2).stripTrailing(), extra);
  }

  static DutyBasis.Builder basis(int termMonths, String monthlyRent) {
    return DutyBasis.builder(
        "ZZ",
        InstrumentKind.LEASE,
        Usage.RESIDENTIAL,
        LocalDate.of(2025, 6, 1),
        termMonths,
        new BigDecimal(monthlyRent));
  }
}
