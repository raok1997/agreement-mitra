package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.DutyBasis;
import in.agreementmitra.rules.DutyOutcome;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * FICTIONAL extension for the test-only {@code ZZ-lease-commercial} rule. Exercises all three
 * hooks; encodes no real law. A {@code @Component} so any Spring context built from the test
 * classpath (where the ZZ rule that references it also lives) finds it.
 */
@Component
class ZzTestExtension implements DutyExtension {

  static final String ID = "zz-test";
  static final String EXCESS_DEPOSIT = "EXCESS_DEPOSIT";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Set<String> providedQuantities() {
    return Set.of(EXCESS_DEPOSIT);
  }

  @Override
  public Optional<DutyOutcome> precheck(DutyBasis basis, RuleSet rule) {
    return basis.nonRefundableDeposit().signum() > 0
        ? Optional.of(
            new DutyOutcome.NeedsAdjudication(
                "non-refundable deposit (fictional rule)", rule.ref()))
        : Optional.empty();
  }

  @Override
  public Quantities extraQuantities(Quantities quantities, DutyBasis basis, RuleSet rule) {
    BigDecimal cap = basis.monthlyRent().multiply(rule.params().get("depositMonthsCap"));
    return quantities.with(
        EXCESS_DEPOSIT, basis.refundableDeposit().subtract(cap).max(BigDecimal.ZERO));
  }

  @Override
  public BigDecimal adjust(BigDecimal duty, DutyBasis basis, Quantities quantities, RuleSet rule) {
    return duty.add(BigDecimal.valueOf(25));
  }
}
