package in.agreementmitra.signing.agreement;

import in.agreementmitra.documents.api.TemplateDetail;
import in.agreementmitra.rules.DutyBasis;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds the stamp duty calculator's {@link DutyBasis} from an agreement and its pinned template's
 * dimensions (state-stamp-duty-quoting, design D5). Only amounts, dates, a term, a state code and
 * enum classifiers cross into {@code rules} -- never a party, contact or address.
 *
 * <p>Conservative where the capture is silent: no rent-free months (fit-out is not necessarily
 * rent-free) and escalation only when the capture states it. Empty when the agreement cannot be
 * described: no usable state, an unknown template type, or facts the basis rejects.
 */
final class DutyBasisMapper {

  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private static final String AGREEMENT_DATE = "agreementDate";
  private static final String ESCALATION_PERCENT = "rentEscalationPercent";
  private static final int ESCALATION_EVERY_MONTHS = 12;

  private DutyBasisMapper() {}

  static Optional<DutyBasis> from(
      Agreement agreement, TemplateDetail.Dimensions dimensions, Clock clock) {
    if (dimensions == null || dimensions.state() == null || dimensions.state().isBlank()) {
      return Optional.empty();
    }
    Optional<DutyBasis.Usage> usage = usageOf(dimensions.type());
    if (usage.isEmpty()) {
      return Optional.empty();
    }
    try {
      BigDecimal escalation = decimal(capture(agreement, ESCALATION_PERCENT));
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

  /** The captured execution date; else today in India, the same rule the draft renders with. */
  static LocalDate executionDate(Agreement agreement, Clock clock) {
    String captured = capture(agreement, AGREEMENT_DATE);
    if (captured != null) {
      try {
        return LocalDate.parse(captured.trim());
      } catch (DateTimeParseException ignored) {
        // An unparseable capture renders as today too; fall through.
      }
    }
    return LocalDate.now(clock.withZone(INDIA));
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

  private static BigDecimal decimal(String value) {
    if (value == null) {
      return null;
    }
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
