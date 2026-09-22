package in.agreementmitra.rules.duty;

import in.agreementmitra.rules.CatalogRef;
import in.agreementmitra.rules.StampOffer;
import java.time.LocalDate;
import java.util.List;

/**
 * The stamp media one state issues over an effective window (design D8). Versioned independently of
 * duty rules: a treasury changing issued denominations is not a change in duty law.
 */
record StampPaperCatalog(
    String state,
    String sourceName,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String source,
    List<Medium> media,
    StampOffer offer,
    String contentHash) {

  StampPaperCatalog {
    media = List.copyOf(media);
  }

  CatalogRef ref() {
    return new CatalogRef(state, source, contentHash, offer);
  }

  boolean inEffectOn(LocalDate date) {
    return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
  }

  enum MediumKind {
    DENOMINATIONS,
    ANY_AMOUNT
  }

  /**
   * One way to buy stamp value.
   *
   * @param denominationsPaise distinct face values, largest first; empty for {@code ANY_AMOUNT}
   * @param maxPapers papers one instrument may carry; 1 for {@code ANY_AMOUNT}
   * @param minimumPaise smallest certificate for {@code ANY_AMOUNT}; 0 for {@code DENOMINATIONS}
   */
  record Medium(
      String id, MediumKind kind, List<Long> denominationsPaise, int maxPapers, long minimumPaise) {
    Medium {
      denominationsPaise = List.copyOf(denominationsPaise);
    }
  }
}
