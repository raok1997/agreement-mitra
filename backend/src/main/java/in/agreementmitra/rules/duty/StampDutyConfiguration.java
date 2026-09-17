package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.StampDutyCalculator;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the stamp duty calculator. Rule and catalog data load when the context starts, so malformed
 * data fails startup. Locations are overridable only so tests can point at other fixtures;
 * production ships the defaults.
 *
 * <p>{@code rules.stamp-duty.allow-unreviewed} (default false) lets customers be charged on rules
 * with no current counsel review. It exists for sandbox and founding-team beta only, so when it is
 * on the startup log names every unreviewed rule that may now be charged -- a deployment charging
 * on unreviewed duty law is visible, never silent (state-stamp-duty-quoting, design D3).
 */
@Configuration(proxyBeanMethods = false)
class StampDutyConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StampDutyConfiguration.class);

  @Bean
  StampDutyCalculator stampDutyCalculator(
      @Value("${rules.stamp-duty.locations:classpath*:rules/stamp-duty/**/*.yaml}")
          String[] ruleLocations,
      @Value("${rules.stamp-paper.locations:classpath*:rules/stamp-paper/*.yaml}")
          String[] catalogLocations,
      @Value("${rules.stamp-duty.allow-unreviewed:false}") boolean allowUnreviewed,
      ObjectProvider<DutyExtension> extensionBeans) {
    List<DutyExtension> extensions = extensionBeans.orderedStream().toList();
    DutyEngine engine =
        build(
            Arrays.asList(ruleLocations),
            Arrays.asList(catalogLocations),
            extensions,
            allowUnreviewed);
    logRules(engine, allowUnreviewed);
    return engine;
  }

  /** The same wiring without Spring, for tests. */
  static DutyEngine build(
      List<String> ruleLocations,
      List<String> catalogLocations,
      List<DutyExtension> extensions,
      boolean allowUnreviewed) {
    Map<String, Set<String>> provided =
        extensions.stream()
            .collect(
                Collectors.toMap(
                    DutyExtension::id,
                    DutyExtension::providedQuantities,
                    (a, b) -> {
                      throw new IllegalStateException("duplicate DutyExtension id");
                    }));
    List<RuleSet> rules = new RuleSetLoader(provided).load(RuleSetLoader.resolve(ruleLocations));
    List<StampPaperCatalog> catalogs =
        new StampPaperCatalogLoader().load(RuleSetLoader.resolve(catalogLocations));
    return new DutyEngine(
        new RuleSetRegistry(rules), catalogs, extensions, new StampPaperPlanner(), allowUnreviewed);
  }

  private static void logRules(DutyEngine engine, boolean allowUnreviewed) {
    List<RuleSet> rules = engine.rules();
    log.info(
        "stamp duty rules loaded: {} (allow-unreviewed={})",
        rules.stream()
            .map(r -> r.id() + (r.ref().reviewed() ? "[reviewed]" : "[UNREVIEWED]"))
            .toList(),
        allowUnreviewed);
    List<String> unreviewed =
        rules.stream().filter(r -> !r.ref().reviewed()).map(RuleSet::id).toList();
    if (allowUnreviewed && !unreviewed.isEmpty()) {
      log.warn(
          "rules.stamp-duty.allow-unreviewed=true: customers may be charged on UNREVIEWED stamp duty"
              + " rules {} -- sandbox/beta only",
          unreviewed);
    }
  }
}
