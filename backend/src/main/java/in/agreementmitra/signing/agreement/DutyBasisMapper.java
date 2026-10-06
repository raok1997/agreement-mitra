package in.agreementmitra.signing.agreement;

import in.agreementmitra.PlausibleDates;
import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Builds the stamp duty calculator's {@link DutyBasis} from an agreement and its pinned template's
 * dimensions (state-stamp-duty-quoting, design D5). Only amounts, dates, a term, a state code and
 * enum classifiers cross into {@code rules} -- never a party, contact or address.
 *
 * <p>Escalation and execution date are resolved the way the deed renders them, so the quote is the
 * duty of the deed the parties sign: a usable captured value first, then the deed's own fallback
 * (the template field's default; the draft's recorded execution date). Conservative where both are
 * silent: no rent-free months (fit-out is not necessarily rent-free) and no escalation. Empty when
 * the agreement cannot be described: no usable state, an unknown template type, a template that no
 * longer resolves, or facts the basis rejects.
 */
final class DutyBasisMapper {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private static final String AGREEMENT_DATE = "agreementDate";
  static final String ESCALATION_PERCENT = "rentEscalationPercent";
  private static final int ESCALATION_EVERY_MONTHS = 12;
  private static final long MAX_ESCALATION_PERCENT = 100;

  /**
   * The template's default for {@code rentEscalationPercent}: {@link #NOT_FOUND} when the template
   * no longer resolves, {@link #NONE} when it declares no whole-number default in the field's
   * range, else the value.
   */
  record DefaultLookup(boolean found, BigDecimal value) {
    static final DefaultLookup NOT_FOUND = new DefaultLookup(false, null);
    static final DefaultLookup NONE = new DefaultLookup(true, null);

    static DefaultLookup of(long value) {
      BigDecimal usable = percent(value);
      return usable == null ? NONE : new DefaultLookup(true, usable);
    }
  }

  private DutyBasisMapper() {}

  /**
   * @param escalationDefault consulted only when the captured escalation is not usable -- a lookup
   *     resolves the template, which is not cheap
   */
  static Optional<DutyBasis> from(
      Agreement agreement,
      TemplateDetail.Dimensions dimensions,
      Supplier<DefaultLookup> escalationDefault,
      Clock clock) {
    if (dimensions == null || dimensions.state() == null || dimensions.state().isBlank()) {
      return Optional.empty();
    }
    Optional<DutyBasis.Usage> usage = usageOf(dimensions.type());
    if (usage.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal escalation = capturedEscalation(agreement);
    if (escalation == null) {
      DefaultLookup lookup = escalationDefault.get();
      if (!lookup.found()) {
        return Optional.empty();
      }
      escalation = lookup.value();
    }
    try {
      boolean escalates = escalation != null && escalation.signum() > 0;
      return Optional.of(
          DutyBasis.builder(
                  dimensions.state(),
                  DutyBasis.InstrumentKind.LEASE,
                  usage.get(),
                  executionDate(agreement, clock),
                  agreement.termMonths(),
                  agreement.monthlyRent())
              .escalation(
                  escalates ? escalation : BigDecimal.ZERO, escalates ? ESCALATION_EVERY_MONTHS : 0)
              .refundableDeposit(agreement.securityDeposit())
              .build());
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
  }

  /**
   * The captured execution date when it parses and is plausible; else the draft's recorded
   * execution date; else today in India -- the order a stored draft's re-render fills it with.
   */
  static LocalDate executionDate(Agreement agreement, Clock clock) {
    String captured = capture(agreement, AGREEMENT_DATE);
    if (captured != null) {
      try {
        LocalDate date = LocalDate.parse(captured.trim());
        if (PlausibleDates.isPlausible(date)) {
          return date;
        }
      } catch (DateTimeParseException ignored) {
        // The deed does not render an unparseable capture either; fall through.
      }
    }
    if (agreement.draftExecutionDate() != null) {
      return agreement.draftExecutionDate();
    }
    return LocalDate.now(clock.withZone(INDIA));
  }

  /**
   * A whole number in [0, 100] parsed exactly as the deed's INT field coerces text; null otherwise,
   * so no other value -- of any magnitude or scale -- reaches the rent arithmetic.
   */
  private static BigDecimal capturedEscalation(Agreement agreement) {
    String value = capture(agreement, ESCALATION_PERCENT);
    if (value == null) {
      return null;
    }
    try {
      return percent(Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The deed field's range, applied to a captured value and a template default alike. */
  private static BigDecimal percent(long value) {
    return value >= 0 && value <= MAX_ESCALATION_PERCENT ? BigDecimal.valueOf(value) : null;
  }

  private static Optional<DutyBasis.Usage> usageOf(String type) {
    if (type == null) {
      return Optional.empty();
    }
    return switch (type.trim().toLowerCase(Locale.ROOT)) {
      case "residential" -> Optional.of(DutyBasis.Usage.RESIDENTIAL);
      case "commercial" -> Optional.of(DutyBasis.Usage.COMMERCIAL);
      default -> Optional.empty();
    };
  }

  private static String capture(Agreement agreement, String key) {
    CaptureState state = agreement.captureState();
    if (state == null) {
      return null;
    }
    String value = state.data().get(key);
    return value == null || value.isBlank() ? null : value;
  }
}
