package in.agreementmitra.signing.agreement;

import static in.agreementmitra.signing.agreement.StampQuoteFixtures.challan;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.paper;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.paperUnplannable;
import static in.agreementmitra.signing.agreement.StampQuoteFixtures.quoted;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bounded stamp options (design D6). Amounts in paise. */
class StampOptionsTest {

  @Test
  void recommendedIsTheCheapestPlannableValueAtOrAboveDuty() {
    // duty 440: stamp paper plans 450, challan plans 440 -> challan is recommended
    StampOptions options = StampOptions.of(quoted(44000, paper(45000, 44000), challan(44000)));

    assertThat(options.recommended())
        .hasValueSatisfying(
            o -> {
              assertThat(o.stampValuePaise()).isEqualTo(44000);
              assertThat(o.mediumId()).isEqualTo("challan");
              assertThat(o.belowDuty()).isFalse();
            });
  }

  @Test
  void tiesKeepCatalogMediaOrder() {
    StampOptions options = StampOptions.of(quoted(4000, paper(4000, 4000), challan(4000)));

    assertThat(options.recommended())
        .hasValueSatisfying(o -> assertThat(o.mediumId()).isEqualTo("stamp-paper"));
  }

  @Test
  void singlePapersStrictlyBelowDutyAreOfferedLargestFirst() {
    StampOptions options = StampOptions.of(quoted(44000, paper(45000, 44000), challan(44000)));

    assertThat(options.options())
        .extracting(StampOptions.Option::stampValuePaise)
        .containsExactly(44000L, 10000L, 5000L, 2000L, 1000L);
    assertThat(options.options().subList(1, 5))
        .allSatisfy(
            o -> {
              assertThat(o.belowDuty()).isTrue();
              assertThat(o.recommended()).isFalse();
            });
  }

  @Test
  void aDenominationEqualToDutyIsNotBelowDuty() {
    StampOptions options = StampOptions.of(quoted(10000, paper(10000, 10000), challan(10000)));

    assertThat(options.options())
        .extracting(StampOptions.Option::stampValuePaise)
        .containsExactly(10000L, 5000L, 2000L, 1000L);
  }

  @Test
  void noRecommendedOptionWhenNothingCanPlanTheDuty() {
    StampOptions options = StampOptions.of(quoted(84000, paperUnplannable()));

    assertThat(options.recommended()).isEmpty();
    assertThat(options.options()).allSatisfy(o -> assertThat(o.belowDuty()).isTrue());
  }

  private static final in.agreementmitra.rules.CatalogRef SINGLE_100 =
      new in.agreementmitra.rules.CatalogRef(
          "TG",
          "FAQ",
          "b".repeat(64),
          new in.agreementmitra.rules.StampOffer(
              in.agreementmitra.rules.StampOffer.Mode.SINGLE_PAPERS,
              java.util.List.of(10000L),
              10000L));

  @Test
  void aSinglePapersOfferShowsOnlyThoseValuesWithThePreselectRecommended() {
    // TG: duty INR 832, only a single INR 100 paper is offered, pre-selected, below the duty.
    StampOptions options =
        StampOptions.of(
            StampQuoteFixtures.quoted(SINGLE_100, 83200, paperUnplannable(), challan(83200)));

    assertThat(options.options())
        .containsExactly(new StampOptions.Option(10000L, true, true, "stamp-paper"));
    assertThat(options.find(83200)).isEmpty();
    assertThat(options.recommended()).isPresent();
  }

  @Test
  void aSinglePaperAtOrAboveADutyIsNotBelowDuty() {
    StampOptions options =
        StampOptions.of(
            StampQuoteFixtures.quoted(SINGLE_100, 4000, paper(4000, 4000), challan(4000)));

    assertThat(options.options())
        .containsExactly(new StampOptions.Option(10000L, false, true, "stamp-paper"));
  }

  @Test
  void aPlannedJurisdictionPreSelectsTheExactDutyAndOffersLowerPapersAsOverrides() {
    // The Karnataka shape (ka-rental-and-commercial-templates): an any-amount e-stamp medium plans
    // the exact duty, so the recommended option IS the legal duty and is not marked below it, while
    // the paper denominations below it stay on offer as choices the customer may deliberately take.
    // This is the contrast with the Telangana single-papers case two tests above, where the
    // pre-selected option is BELOW the duty and the exact duty is not offered at all.
    in.agreementmitra.rules.CatalogRef karnataka =
        new in.agreementmitra.rules.CatalogRef(
            "KA", "Kaveri / SHCIL", "c".repeat(64), in.agreementmitra.rules.StampOffer.PLANNED);
    in.agreementmitra.rules.StampPlan eStamp =
        new in.agreementmitra.rules.StampPlan(
            "e-stamp",
            java.util.List.of(),
            new in.agreementmitra.rules.StampPlan.Planned(
                java.util.List.of(new in.agreementmitra.rules.StampPlan.Paper(170000, 1)),
                170000,
                0));
    in.agreementmitra.rules.StampPlan papers =
        new in.agreementmitra.rules.StampPlan(
            "stamp-paper",
            java.util.List.of(50000L, 20000L, 10000L, 5000L, 2000L),
            new in.agreementmitra.rules.StampPlan.Planned(
                java.util.List.of(new in.agreementmitra.rules.StampPlan.Paper(50000, 4)),
                200000,
                30000));

    StampOptions options =
        StampOptions.of(StampQuoteFixtures.quoted(karnataka, 170000, eStamp, papers));

    assertThat(options.recommended())
        .hasValueSatisfying(
            o -> {
              assertThat(o.stampValuePaise()).isEqualTo(170000);
              assertThat(o.mediumId()).isEqualTo("e-stamp");
              assertThat(o.belowDuty()).isFalse();
            });
    assertThat(options.options())
        .extracting(StampOptions.Option::stampValuePaise)
        .containsExactly(170000L, 50000L, 20000L, 10000L, 5000L, 2000L);
    assertThat(options.options().subList(1, options.options().size()))
        .allSatisfy(
            o -> {
              assertThat(o.belowDuty()).isTrue();
              assertThat(o.recommended()).isFalse();
            });
  }

  @Test
  void findMatchesOnlyOfferedValues() {
    StampOptions options = StampOptions.of(quoted(84000, paperUnplannable(), challan(84000)));

    assertThat(options.find(84000)).isPresent();
    assertThat(options.find(10000)).hasValueSatisfying(o -> assertThat(o.belowDuty()).isTrue());
    assertThat(options.find(50000)).isEmpty();
  }
}
