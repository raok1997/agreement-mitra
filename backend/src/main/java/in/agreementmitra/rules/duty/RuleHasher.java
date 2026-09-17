package in.agreementmitra.rules.duty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Content hash of a <b>merged</b> rule's computational content (design D6). Excludes {@code cases}
 * (adding a test must not invalidate a review) and {@code counselReview} (the review cites the
 * hash, so including it would be circular). Includes the legal reference: a changed citation is a
 * changed rule. Because the rule is hashed after the base merge, a base change changes every state
 * hash.
 */
final class RuleHasher {

  private RuleHasher() {}

  static String hash(RuleSet rule) {
    Map<String, Object> content = new TreeMap<>();
    content.put("id", rule.id());
    content.put("state", rule.state());
    content.put("instrumentKind", rule.instrumentKind().name());
    content.put("usage", rule.usage().name());
    content.put("effectiveFrom", rule.effectiveFrom().toString());
    content.put("effectiveTo", rule.effectiveTo() == null ? null : rule.effectiveTo().toString());
    content.put("legalReference", rule.legalReference());
    content.put("extension", rule.extension());
    Map<String, Object> params = new TreeMap<>();
    rule.params().forEach((k, v) -> params.put(k, CanonicalHash.decimal(v)));
    content.put("params", params);
    List<Object> slabs = new ArrayList<>();
    for (RuleSet.Slab slab : rule.slabs()) {
      Map<String, Object> s = new TreeMap<>();
      s.put("minMonths", slab.minMonths());
      s.put("maxMonths", slab.maxMonths());
      s.put("consideration", slab.consideration());
      s.put("ratePercent", CanonicalHash.decimal(slab.ratePercent()));
      s.put("fixedAmount", CanonicalHash.decimal(slab.fixedAmount()));
      slabs.add(s);
    }
    content.put("slabs", slabs);
    content.put("minimumAmount", CanonicalHash.decimal(rule.bounds().minimum()));
    content.put("maximumAmount", CanonicalHash.decimal(rule.bounds().maximum()));
    List<Object> surcharges = new ArrayList<>();
    for (RuleSet.Surcharge surcharge : rule.surcharges()) {
      Map<String, Object> s = new TreeMap<>();
      s.put("name", surcharge.name());
      s.put("percentOfDuty", CanonicalHash.decimal(surcharge.percentOfDuty()));
      surcharges.add(s);
    }
    content.put("surcharges", surcharges);
    content.put("counterpartDuty", CanonicalHash.decimal(rule.counterpartDuty()));
    Map<String, Object> rounding = new TreeMap<>();
    rounding.put("mode", rule.rounding().mode().name());
    rounding.put("unitRupees", rule.rounding().unitRupees());
    content.put("rounding", rounding);
    content.put(
        "registrationRequiredWhenTermMonthsOver", rule.registration().requiredWhenTermMonthsOver());
    return CanonicalHash.of(content);
  }
}
