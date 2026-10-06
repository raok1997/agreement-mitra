package in.agreementmitra.rules;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One step of a quote's breakdown, in rupees.
 *
 * <p>Replay semantics, which make the breakdown auditable: {@link Kind#QUANTITY} lines sum to the
 * consideration and are informational; an {@link Kind#ESCALATION} line is informational too -- the
 * rupees rent escalation added to the consideration -- and replay skips it; the single {@link
 * Kind#BASE} line is the duty from the slab's rate or fixed amount; every other kind is a <b>signed
 * delta</b>. So {@code BASE + sum of deltas} equals the quoted amount.
 */
public record DutyLine(Kind kind, String label, BigDecimal amount) {

  /**
   * What a line represents; see the replay semantics on the record.
   *
   * <p>{@code SLAB_MINIMUM} / {@code SLAB_MAXIMUM} are the selected term slab's own bounds and are
   * applied before the rule-level {@code MINIMUM} / {@code MAXIMUM}. They are separate kinds rather
   * than a relabelled MINIMUM/MAXIMUM because a customer shown a duty of INR 500 against a
   * consideration of INR 340,000 is owed the reason, and "this band is capped" is a different fact
   * from "this rule is capped".
   *
   * <p>{@code ESCALATION} explains a quantity rather than changing the duty: its amount is the
   * consideration with escalation minus the consideration without it.
   */
  public enum Kind {
    QUANTITY,
    ESCALATION,
    BASE,
    SLAB_MINIMUM,
    SLAB_MAXIMUM,
    MINIMUM,
    MAXIMUM,
    EXTENSION,
    SURCHARGE,
    COUNTERPART,
    ROUNDING;

    /** Whether the line's amount is a delta applied to the running duty. */
    public boolean isDelta() {
      return this != QUANTITY && this != ESCALATION && this != BASE;
    }
  }

  public DutyLine {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(amount, "amount");
  }
}
