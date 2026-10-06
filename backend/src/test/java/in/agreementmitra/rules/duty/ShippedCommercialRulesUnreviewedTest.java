package in.agreementmitra.rules.duty;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.rules.DutyBasis.Usage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tripwire for the residential-only release. Commercial is withheld from paid fulfilment only
 * because its shipped rules carry no counsel review; adding one makes commercial payable while the
 * picker still hides it. This test fails on that review so the un-hiding lands in the same commit.
 */
class ShippedCommercialRulesUnreviewedTest {

  private static final String UNHIDE =
      "A shipped commercial rule now carries a counsel review: commercial is payable again. In the"
          + " same commit, lift the type check in isOffered (frontend TemplatePicker.vue), restore"
          + " commercial in the FAQ \"Which cities do you serve?\" answer (LandingPage.vue +"
          + " index.html JSON-LD), and retire this test.";

  @Test
  void shippedCommercialRulesAreUnreviewed() {
    List<RuleSet> commercial =
        new RuleSetLoader(Map.of(ZzTestExtension.ID, new ZzTestExtension().providedQuantities()))
            .load(RuleSetLoader.resolve(TestRules.DEFAULT_RULE_LOCATIONS)).stream()
                .filter(r -> !r.state().equals("ZZ"))
                .filter(r -> r.usage() == Usage.COMMERCIAL)
                .toList();

    assertThat(commercial)
        .extracting(RuleSet::state)
        .as("a new state's commercial rule also needs the picker and FAQ to stay residential-only")
        .containsExactlyInAnyOrder("TG", "KA");
    assertThat(commercial).allSatisfy(r -> assertThat(r.ref().reviewed()).as(UNHIDE).isFalse());
  }
}
