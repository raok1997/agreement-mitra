package in.agreementmitra.rules;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One step of a quote's breakdown, in rupees.
 *
 * <p>Replay semantics, which make the breakdown auditable: {@link Kind#QUANTITY} lines sum to the
 * consideration and are informational; the single {@link Kind#BASE} line is the duty from the
 * slab's rate or fixed amount; every later kind is a <b>signed delta</b>. So {@code BASE + sum of
 * deltas} equals the quoted amount.
 */
public record DutyLine(Kind kind, String label, BigDecimal amount) {

  /** What a line represents; see the replay semantics on the record. */
  public enum Kind {
    QUANTITY,
    BASE,
    MINIMUM,
    MAXIMUM,
    EXTENSION,
    SURCHARGE,
    COUNTERPART,
    ROUNDING;

    /** Whether the line's amount is a delta applied to the running duty. */
    public boolean isDelta() {
      return this != QUANTITY && this != BASE;
    }
  }

  public DutyLine {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(amount, "amount");
  }
}
