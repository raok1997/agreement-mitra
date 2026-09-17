package in.agreementmitra.rules.duty;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.rules.StampPlan;
import in.agreementmitra.rules.StampPlan.Paper;
import in.agreementmitra.rules.StampPlan.Planned;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Stamp paper planning (design D8): every spec scenario plus optimality properties. */
class StampPaperPlannerTest {

  private final StampPaperPlanner planner = new StampPaperPlanner();

  private static StampPaperCatalog.Medium physical(int maxPapers, long... rupees) {
    List<Long> paise = new ArrayList<>();
    for (long r : rupees) {
      paise.add(r * 100);
    }
    paise.sort(Comparator.reverseOrder());
    return new StampPaperCatalog.Medium(
        "physical", StampPaperCatalog.MediumKind.DENOMINATIONS, paise, maxPapers, 0);
  }

  private static final long[] STANDARD = {10, 20, 50, 100, 500, 1000};

  private Planned planned(long dutyRupees, StampPaperCatalog.Medium medium) {
    StampPlan plan = planner.plan(dutyRupees * 100, medium);
    assertThat(plan.result()).isInstanceOf(Planned.class);
    return (Planned) plan.result();
  }

  @Test
  void exactDenominationAvailable() {
    Planned p = planned(100, physical(3, STANDARD));

    assertThat(p.papers()).containsExactly(new Paper(10000, 1));
    assertThat(p.totalPaise()).isEqualTo(10000);
    assertThat(p.excessPaise()).isZero();
  }

  @Test
  void dutyBetweenDenominationsRoundsUpToTheNearestAchievableValue() {
    Planned p = planned(660, physical(3, STANDARD));

    assertThat(p.papers()).containsExactly(new Paper(50000, 1), new Paper(10000, 2));
    assertThat(p.totalPaise()).isEqualTo(70000);
    assertThat(p.excessPaise()).isEqualTo(4000);
  }

  @Test
  void aTighterPaperLimitRaisesThePayableValue() {
    Planned p = planned(660, physical(1, STANDARD));

    assertThat(p.papers()).containsExactly(new Paper(100000, 1));
    assertThat(p.excessPaise()).isEqualTo(34000);
  }

  @Test
  void equalTotalsPreferFewerPapers() {
    Planned p = planned(900, physical(2, 500, 1000));

    assertThat(p.papers()).containsExactly(new Paper(100000, 1));
  }

  @Test
  void equalTotalsAndCountsPreferLargerDenominationsFirst() {
    // 60 = 50 + 10 = 30 + 30 (same total, same count): larger first wins.
    Planned p = planned(60, physical(2, 10, 30, 50));

    assertThat(p.papers()).containsExactly(new Paper(5000, 1), new Paper(1000, 1));
  }

  @Test
  void findsTheOptimumWhereGreedyWouldOvercharge() {
    // Greedy largest-first gives 60 + 60 = 120; the optimum is 50 + 50 = 100.
    Planned p = planned(100, physical(2, 60, 50));

    assertThat(p.papers()).containsExactly(new Paper(5000, 2));
    assertThat(p.totalPaise()).isEqualTo(10000);
  }

  @Test
  void anyAmountMediumBelowItsMinimum() {
    StampPaperCatalog.Medium eStamp =
        new StampPaperCatalog.Medium(
            "e-stamp", StampPaperCatalog.MediumKind.ANY_AMOUNT, List.of(), 1, 1000);

    Planned below = planned(5, eStamp);
    Planned above = planned(2500, eStamp);

    assertThat(below.papers()).containsExactly(new Paper(1000, 1));
    assertThat(below.excessPaise()).isEqualTo(500);
    assertThat(above.totalPaise()).isEqualTo(250000);
    assertThat(above.excessPaise()).isZero();
  }

  @Test
  void dutyAboveTheMediumsReachIsUnplannable() {
    StampPlan plan = planner.plan(250000, physical(2, STANDARD));

    assertThat(plan.mediumId()).isEqualTo("physical");
    assertThat(plan.result()).isInstanceOf(StampPlan.Unplannable.class);
    assertThat(((StampPlan.Unplannable) plan.result()).reason()).contains("at most 2 paper(s)");
  }

  @Test
  void zeroDutyStillNeedsOnePaperAndTakesTheSmallest() {
    Planned p = planned(0, physical(3, STANDARD));

    assertThat(p.papers()).containsExactly(new Paper(1000, 1));
  }

  @RepeatedTest(200)
  void planTotalIsNeverBelowDutyAndIsOptimal() {
    Random random = new Random();
    int count = 1 + random.nextInt(5);
    long[] rupees = random.longs(count, 1, 200).distinct().toArray();
    int maxPapers = 1 + random.nextInt(4);
    long duty = random.nextInt(600);
    StampPaperCatalog.Medium medium = physical(maxPapers, rupees);

    StampPlan plan = planner.plan(duty * 100, medium);
    long bruteForce = bruteForceBest(duty * 100, medium.denominationsPaise(), maxPapers);

    if (bruteForce == Long.MAX_VALUE) {
      assertThat(plan.result()).isInstanceOf(StampPlan.Unplannable.class);
    } else {
      Planned p = (Planned) plan.result();
      assertThat(p.totalPaise()).isGreaterThanOrEqualTo(duty * 100).isEqualTo(bruteForce);
      assertThat(p.papers().stream().mapToLong(x -> x.denominationPaise() * x.count()).sum())
          .isEqualTo(p.totalPaise());
      assertThat(p.papers().stream().mapToInt(Paper::count).sum()).isLessThanOrEqualTo(maxPapers);
    }
  }

  /** Independent check: smallest reachable total >= duty over all sequences (not multisets). */
  private static long bruteForceBest(long duty, List<Long> denominations, int maxPapers) {
    long best = Long.MAX_VALUE;
    List<Long> totals = new ArrayList<>(List.of(0L));
    for (int k = 1; k <= maxPapers; k++) {
      List<Long> next = new ArrayList<>();
      for (long t : totals) {
        for (long d : denominations) {
          long sum = t + d;
          next.add(sum);
          if (sum >= duty && sum < best) {
            best = sum;
          }
        }
      }
      totals = next;
    }
    return best;
  }
}
