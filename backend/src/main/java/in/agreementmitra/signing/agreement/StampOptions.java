package in.agreementmitra.signing.agreement;

import in.agreementmitra.rules.DutyOutcome;
import in.agreementmitra.rules.StampOffer;
import in.agreementmitra.rules.StampPlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bounded stamp values a customer may buy for a quoted agreement (state-stamp-duty-quoting,
 * design D6): the <b>recommended</b> option -- the smallest plannable stamp value at or above the
 * legal duty across the jurisdiction's media -- and each single stamp paper denomination strictly
 * below the duty. No other value is ever acceptable, and there is no free-form amount.
 *
 * <p>Java-{@code public} so the payment and api packages can use it; still Modulith-internal.
 */
public record StampOptions(long dutyPaise, List<Option> options) {

  /**
   * The current version of the under-stamping warning a customer must acknowledge to buy a stamp
   * value below the legal duty. Bump it whenever the warning text changes, so an acknowledgement
   * given against older wording is refused rather than silently reused.
   */
  public static final String UNDER_STAMP_WARNING_VERSION = "under-stamp-v1";

  public StampOptions {
    options = List.copyOf(options);
  }

  /**
   * One buyable stamp value.
   *
   * @param mediumId the stamp medium it is bought through (e.g. {@code stamp-paper}, {@code
   *     challan})
   */
  public record Option(
      long stampValuePaise, boolean belowDuty, boolean recommended, String mediumId) {}

  public static StampOptions of(DutyOutcome.Quoted quote) {
    long duty = quote.amountPaise();
    List<Option> options = new ArrayList<>();

    StampOffer offer = quote.catalog().offer();
    if (offer.mode() == StampOffer.Mode.SINGLE_PAPERS) {
      // The state offers only these single papers, whether above or below the duty; the catalog's
      // preselect is the default ("recommended") option.
      for (long denomination : offer.denominationsPaise()) {
        options.add(
            new Option(
                denomination,
                denomination < duty,
                denomination == offer.preselectPaise(),
                mediumIssuing(quote, denomination)));
      }
      return new StampOptions(duty, options);
    }

    // Recommended: cheapest planned total; ties keep catalog media order (the stream is ordered and
    // min() keeps the first of equal elements only with a stable reduce, so do it explicitly).
    StampPlan best = null;
    long bestTotal = Long.MAX_VALUE;
    for (StampPlan plan : quote.stampPlans()) {
      if (plan.result() instanceof StampPlan.Planned planned && planned.totalPaise() < bestTotal) {
        best = plan;
        bestTotal = planned.totalPaise();
      }
    }
    if (best != null) {
      options.add(new Option(bestTotal, false, true, best.mediumId()));
    }

    Map<Long, Option> below = new LinkedHashMap<>();
    for (StampPlan plan : quote.stampPlans()) {
      for (long denomination : plan.denominationsPaise()) {
        if (denomination < duty) {
          below.putIfAbsent(denomination, new Option(denomination, true, false, plan.mediumId()));
        }
      }
    }
    below.values().stream()
        .sorted(Comparator.comparingLong(Option::stampValuePaise).reversed())
        .forEach(options::add);
    return new StampOptions(duty, options);
  }

  private static String mediumIssuing(DutyOutcome.Quoted quote, long denomination) {
    return quote.stampPlans().stream()
        .filter(p -> p.denominationsPaise().contains(denomination))
        .map(StampPlan::mediumId)
        .findFirst()
        .orElse("stamp-paper");
  }

  public Optional<Option> recommended() {
    return options.stream().filter(Option::recommended).findFirst();
  }

  /** The option with exactly this stamp value, if it is one of the offered options. */
  public Optional<Option> find(long stampValuePaise) {
    return options.stream().filter(o -> o.stampValuePaise() == stampValuePaise).findFirst();
  }
}
