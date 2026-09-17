package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.StampPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans the stamp paper that meets a legal duty through one medium (design D8).
 *
 * <p>For DENOMINATIONS: exhaustive enumeration of multisets of at most {@code maxPapers} papers,
 * choosing the smallest total at or above the duty, then the fewest papers, then larger
 * denominations first. Greedy largest-first is not optimal for arbitrary denomination sets, and the
 * excess is real customer money. The catalog loader bounds the enumeration size.
 */
final class StampPaperPlanner {

  StampPlan plan(long dutyPaise, StampPaperCatalog.Medium medium) {
    StampPlan.Result result =
        switch (medium.kind()) {
          case ANY_AMOUNT -> {
            long total = Math.max(dutyPaise, medium.minimumPaise());
            yield new StampPlan.Planned(
                List.of(new StampPlan.Paper(total, 1)), total, total - dutyPaise);
          }
          case DENOMINATIONS -> planDenominations(dutyPaise, medium);
        };
    return new StampPlan(medium.id(), medium.denominationsPaise(), result);
  }

  private static StampPlan.Result planDenominations(long dutyPaise, StampPaperCatalog.Medium m) {
    Search search = new Search(dutyPaise, m.denominationsPaise(), m.maxPapers());
    search.explore(0, new ArrayList<>(), 0);
    if (search.best == null) {
      return new StampPlan.Unplannable(
          "no combination of at most "
              + m.maxPapers()
              + " paper(s) reaches the duty of "
              + dutyPaise
              + " paise");
    }
    Map<Long, Integer> grouped = new LinkedHashMap<>();
    search.best.forEach(d -> grouped.merge(d, 1, Integer::sum));
    List<StampPlan.Paper> papers = new ArrayList<>();
    grouped.forEach((d, count) -> papers.add(new StampPlan.Paper(d, count)));
    return new StampPlan.Planned(papers, search.bestTotal, search.bestTotal - dutyPaise);
  }

  /** Depth-first over non-increasing sequences of denominations (sorted largest first). */
  private static final class Search {
    private final long duty;
    private final List<Long> denominations;
    private final int maxPapers;
    private List<Long> best;
    private long bestTotal;

    Search(long duty, List<Long> denominations, int maxPapers) {
      this.duty = duty;
      this.denominations = denominations;
      this.maxPapers = maxPapers;
    }

    void explore(int fromIndex, List<Long> chosen, long total) {
      if (!chosen.isEmpty() && total >= duty) {
        consider(chosen, total);
        return; // adding papers only raises the total
      }
      if (chosen.size() == maxPapers) {
        return;
      }
      for (int i = fromIndex; i < denominations.size(); i++) {
        long next = total + denominations.get(i);
        if (best != null && next > bestTotal) {
          continue;
        }
        chosen.add(denominations.get(i));
        explore(i, chosen, next);
        chosen.remove(chosen.size() - 1);
      }
    }

    private void consider(List<Long> chosen, long total) {
      if (best == null || isBetter(chosen, total)) {
        best = List.copyOf(chosen);
        bestTotal = total;
      }
    }

    private boolean isBetter(List<Long> chosen, long total) {
      if (total != bestTotal) {
        return total < bestTotal;
      }
      if (chosen.size() != best.size()) {
        return chosen.size() < best.size();
      }
      for (int i = 0; i < chosen.size(); i++) {
        int cmp = Long.compare(chosen.get(i), best.get(i));
        if (cmp != 0) {
          return cmp > 0;
        }
      }
      return false;
    }
  }
}
