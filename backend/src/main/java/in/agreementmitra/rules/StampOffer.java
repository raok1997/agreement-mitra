package in.agreementmitra.rules;

import java.util.List;
import java.util.Objects;

/**
 * How a state's stamp options are offered to the customer, declared in its stamp paper catalog.
 * Commercial and operational policy, not duty law: it never changes the legal duty or the plans,
 * only which stamp values are offered and which is pre-selected.
 *
 * @param mode {@link Mode#PLANNED}: offer the cheapest planned value at or above the duty plus each
 *     single paper below it (the default). {@link Mode#SINGLE_PAPERS}: offer only single papers of
 *     {@code denominationsPaise}, whether above or below the duty.
 * @param denominationsPaise for {@code SINGLE_PAPERS}, the single paper values offered, largest
 *     first; empty for {@code PLANNED}
 * @param preselectPaise for {@code SINGLE_PAPERS}, the value pre-selected; null for {@code PLANNED}
 */
public record StampOffer(Mode mode, List<Long> denominationsPaise, Long preselectPaise) {

  public enum Mode {
    PLANNED,
    SINGLE_PAPERS
  }

  public static final StampOffer PLANNED = new StampOffer(Mode.PLANNED, List.of(), null);

  public StampOffer {
    Objects.requireNonNull(mode, "mode");
    denominationsPaise = List.copyOf(denominationsPaise);
  }
}
