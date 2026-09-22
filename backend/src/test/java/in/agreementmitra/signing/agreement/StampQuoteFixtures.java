package in.agreementmitra.signing.agreement;

import in.agreementmitra.rules.CatalogRef;
import in.agreementmitra.rules.DutyLine;
import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.RuleRef;
import in.agreementmitra.rules.StampPlan;
import java.math.BigDecimal;
import java.util.List;

/** Hand-built calculator outcomes for signing-side unit tests (no rules module internals). */
final class StampQuoteFixtures {

  static final RuleRef TG_RULE =
      new RuleRef("TG-lease-residential", "Art. 31", "a".repeat(64), false);
  static final CatalogRef TG_CATALOG = new CatalogRef("TG", "FAQ", "b".repeat(64), null);
  static final List<Long> TG_PAPERS = List.of(10000L, 5000L, 2000L, 1000L);

  private StampQuoteFixtures() {}

  static StampPlan paper(long totalPaise, long dutyPaise) {
    return new StampPlan(
        "stamp-paper",
        TG_PAPERS,
        new StampPlan.Planned(
            List.of(new StampPlan.Paper(totalPaise, 1)), totalPaise, totalPaise - dutyPaise));
  }

  static StampPlan paperUnplannable() {
    return new StampPlan("stamp-paper", TG_PAPERS, new StampPlan.Unplannable("too large"));
  }

  static StampPlan challan(long dutyPaise) {
    return new StampPlan(
        "challan",
        List.of(),
        new StampPlan.Planned(List.of(new StampPlan.Paper(dutyPaise, 1)), dutyPaise, 0));
  }

  static DutyOutcome.Quoted quoted(long dutyPaise, StampPlan... plans) {
    return quoted(TG_CATALOG, dutyPaise, plans);
  }

  static DutyOutcome.Quoted quoted(CatalogRef catalog, long dutyPaise, StampPlan... plans) {
    return new DutyOutcome.Quoted(
        dutyPaise,
        List.of(new DutyLine(DutyLine.Kind.BASE, "base", BigDecimal.valueOf(dutyPaise, 2))),
        TG_RULE,
        true,
        List.of(plans),
        catalog);
  }
}
