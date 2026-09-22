package in.agreementmitra.rules;

import java.util.List;
import java.util.Objects;

/**
 * How the legal duty is met with stamp paper that can actually be bought through one stamp medium.
 *
 * <p>A plan never totals below the legal duty. A medium that cannot reach the duty within its paper
 * limit is {@link Unplannable}, never silently under-stamped.
 */
public record StampPlan(String mediumId, List<Long> denominationsPaise, Result result) {

  /**
   * @param denominationsPaise the face values this medium issues, largest first; empty for a medium
   *     that issues any amount (e.g. a challan)
   */
  public StampPlan {
    Objects.requireNonNull(mediumId, "mediumId");
    denominationsPaise = List.copyOf(denominationsPaise);
    Objects.requireNonNull(result, "result");
  }

  /** The plan for a medium. */
  public sealed interface Result permits Planned, Unplannable {}

  /**
   * @param papers grouped by denomination, largest first
   * @param excessPaise {@code totalPaise} minus the legal duty; never negative
   */
  public record Planned(List<Paper> papers, long totalPaise, long excessPaise) implements Result {
    public Planned {
      papers = List.copyOf(papers);
    }
  }

  /** No combination within the medium's limits reaches the duty. */
  public record Unplannable(String reason) implements Result {}

  /** {@code count} papers of one face value. */
  public record Paper(long denominationPaise, int count) {}
}
