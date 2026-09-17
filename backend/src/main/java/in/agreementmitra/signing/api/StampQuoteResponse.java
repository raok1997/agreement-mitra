package in.agreementmitra.signing.api;

import java.util.List;
import java.util.UUID;

/**
 * The stamp quote shown before payment (state-stamp-duty-quoting, design D7): the legal duty, its
 * breakdown, the registration notice, the rule's citation and review status, and each stamp option
 * with the total payable for it. Carries no party name, contact or address.
 *
 * <p>When an order already exists, the quote is the one <b>frozen</b> with it ({@code frozen =
 * true}) and {@code options} holds only the chosen option.
 *
 * @param available whether the agreement can be paid for; when false only {@code status} is set
 * @param status why: {@code QUOTABLE}, {@code NO_JURISDICTION}, {@code UNSUPPORTED}, {@code
 *     NOT_CHARGEABLE} or {@code UNPLANNABLE}
 * @param warningVersion the under-stamping warning version a below-duty choice must acknowledge
 */
public record StampQuoteResponse(
    UUID agreementId,
    boolean available,
    String status,
    boolean frozen,
    Long dutyMinorUnits,
    String currency,
    List<Line> breakdown,
    Boolean registrationRequired,
    Rule rule,
    String warningVersion,
    List<Option> options) {

  public StampQuoteResponse {
    breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    options = options == null ? List.of() : List.copyOf(options);
  }

  /** One breakdown step; {@code amount} is a plain rupee decimal string. */
  public record Line(String kind, String label, String amount) {}

  /** The rule a quote was computed under. {@code legalReference} is null on a frozen quote. */
  public record Rule(String id, String legalReference, boolean reviewed) {}

  /**
   * One buyable stamp value and the total the customer would pay for it.
   *
   * @param medium how it is bought, e.g. {@code stamp-paper} or {@code challan}
   */
  public record Option(
      long stampValueMinorUnits,
      boolean belowDuty,
      boolean recommended,
      long totalMinorUnits,
      String medium) {}

  public static StampQuoteResponse unavailable(UUID agreementId, String status) {
    return new StampQuoteResponse(
        agreementId, false, status, false, null, null, null, null, null, null, null);
  }
}
