package in.agreementmitra.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * The state-agnostic facts a stamp duty quote is computed from. The same shape serves every state;
 * only the selected rule differs.
 *
 * <p>Deliberately carries <b>no personal data</b>: amounts, dates, a term, a state code and enum
 * classifiers only. Money is exact {@link BigDecimal} in rupees; optional money defaults to zero.
 *
 * @param dutyState the state where the <b>property</b> lies (not the template's state dimension);
 *     trimmed and upper-cased
 * @param executionDate selects the rule version; never the date the quote is requested
 * @param escalationEveryMonths 0 means no escalation
 * @param counterparts total copies including the original; at least 1
 */
public record DutyBasis(
    String dutyState,
    InstrumentKind instrumentKind,
    Usage usage,
    LocalDate executionDate,
    int termMonths,
    BigDecimal monthlyRent,
    BigDecimal escalationPercent,
    int escalationEveryMonths,
    int rentFreeMonths,
    BigDecimal refundableDeposit,
    BigDecimal nonRefundableDeposit,
    BigDecimal advanceRent,
    BigDecimal premium,
    int counterparts) {

  /** The instrument's legal character; decides which stamp article applies. */
  public enum InstrumentKind {
    LEASE,
    LEAVE_AND_LICENCE
  }

  /** What the premises are used for. */
  public enum Usage {
    RESIDENTIAL,
    COMMERCIAL
  }

  public DutyBasis {
    if (dutyState == null || dutyState.isBlank()) {
      throw invalid("dutyState", "is required");
    }
    dutyState = dutyState.trim().toUpperCase(Locale.ROOT);
    require(instrumentKind, "instrumentKind");
    require(usage, "usage");
    require(executionDate, "executionDate");
    if (termMonths < 1) {
      throw invalid("termMonths", "must be at least 1");
    }
    monthlyRent = nonNegative(monthlyRent, "monthlyRent");
    escalationPercent = nonNegative(escalationPercent, "escalationPercent");
    if (escalationEveryMonths < 0) {
      throw invalid("escalationEveryMonths", "must not be negative");
    }
    if (rentFreeMonths < 0 || rentFreeMonths > termMonths) {
      throw invalid("rentFreeMonths", "must be between 0 and termMonths");
    }
    refundableDeposit = nonNegative(refundableDeposit, "refundableDeposit");
    nonRefundableDeposit = nonNegative(nonRefundableDeposit, "nonRefundableDeposit");
    advanceRent = nonNegative(advanceRent, "advanceRent");
    premium = nonNegative(premium, "premium");
    if (counterparts < 1) {
      throw invalid("counterparts", "must be at least 1");
    }
  }

  /** Starts a basis with the mandatory facts; every optional amount defaults to zero. */
  public static Builder builder(
      String dutyState,
      InstrumentKind instrumentKind,
      Usage usage,
      LocalDate executionDate,
      int termMonths,
      BigDecimal monthlyRent) {
    return new Builder(dutyState, instrumentKind, usage, executionDate, termMonths, monthlyRent);
  }

  private static void require(Object value, String field) {
    if (value == null) {
      throw invalid(field, "is required");
    }
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    if (value.signum() < 0) {
      throw invalid(field, "must not be negative");
    }
    return value;
  }

  private static IllegalArgumentException invalid(String field, String problem) {
    return new IllegalArgumentException("DutyBasis." + field + " " + problem);
  }

  /** Fluent construction for the many optional amounts. */
  public static final class Builder {
    private final String dutyState;
    private final InstrumentKind instrumentKind;
    private final Usage usage;
    private final LocalDate executionDate;
    private final int termMonths;
    private final BigDecimal monthlyRent;
    private BigDecimal escalationPercent = BigDecimal.ZERO;
    private int escalationEveryMonths;
    private int rentFreeMonths;
    private BigDecimal refundableDeposit = BigDecimal.ZERO;
    private BigDecimal nonRefundableDeposit = BigDecimal.ZERO;
    private BigDecimal advanceRent = BigDecimal.ZERO;
    private BigDecimal premium = BigDecimal.ZERO;
    private int counterparts = 1;

    private Builder(
        String dutyState,
        InstrumentKind instrumentKind,
        Usage usage,
        LocalDate executionDate,
        int termMonths,
        BigDecimal monthlyRent) {
      this.dutyState = dutyState;
      this.instrumentKind = instrumentKind;
      this.usage = usage;
      this.executionDate = executionDate;
      this.termMonths = termMonths;
      this.monthlyRent = Objects.requireNonNullElse(monthlyRent, BigDecimal.ZERO);
    }

    public Builder escalation(BigDecimal percent, int everyMonths) {
      this.escalationPercent = percent;
      this.escalationEveryMonths = everyMonths;
      return this;
    }

    public Builder rentFreeMonths(int months) {
      this.rentFreeMonths = months;
      return this;
    }

    public Builder refundableDeposit(BigDecimal amount) {
      this.refundableDeposit = amount;
      return this;
    }

    public Builder nonRefundableDeposit(BigDecimal amount) {
      this.nonRefundableDeposit = amount;
      return this;
    }

    public Builder advanceRent(BigDecimal amount) {
      this.advanceRent = amount;
      return this;
    }

    public Builder premium(BigDecimal amount) {
      this.premium = amount;
      return this;
    }

    public Builder counterparts(int count) {
      this.counterparts = count;
      return this;
    }

    public DutyBasis build() {
      return new DutyBasis(
          dutyState,
          instrumentKind,
          usage,
          executionDate,
          termMonths,
          monthlyRent,
          escalationPercent,
          escalationEveryMonths,
          rentFreeMonths,
          refundableDeposit,
          nonRefundableDeposit,
          advanceRent,
          premium,
          counterparts);
    }
  }
}
