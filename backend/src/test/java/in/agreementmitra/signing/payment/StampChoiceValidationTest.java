package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.agreementmitra.StampChoiceInvalidException;
import in.agreementmitra.StampChoiceInvalidException.Reason;
import in.agreementmitra.signing.agreement.StampOptions;
import in.agreementmitra.signing.api.CheckoutRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stamp choice a checkout may carry (state-stamp-duty-quoting, stamp-selection spec): exactly
 * one of the recomputed options, and a below-duty one only with the current warning acknowledged.
 */
class StampChoiceValidationTest {

  // Legal duty INR 440: recommended challan INR 440, then single papers 100/50/20/10 below duty.
  private static final StampOptions OPTIONS =
      new StampOptions(
          44_000L,
          List.of(
              new StampOptions.Option(44_000L, false, true, "challan"),
              new StampOptions.Option(10_000L, true, false, "stamp-paper"),
              new StampOptions.Option(5_000L, true, false, "stamp-paper")));

  private static final CheckoutRequest.Acknowledgement CURRENT =
      new CheckoutRequest.Acknowledgement(StampOptions.UNDER_STAMP_WARNING_VERSION);

  private static void assertRefused(CheckoutRequest request, Reason reason) {
    assertThatThrownBy(() -> PaymentOrderService.validChoice(OPTIONS, request))
        .isInstanceOf(StampChoiceInvalidException.class)
        .satisfies(
            e -> {
              StampChoiceInvalidException ex = (StampChoiceInvalidException) e;
              assertThat(ex.reason()).isEqualTo(reason);
              assertThat(ex.currentWarningVersion())
                  .isEqualTo(StampOptions.UNDER_STAMP_WARNING_VERSION);
            });
  }

  @Test
  void theRecommendedOptionNeedsNoAcknowledgement() {
    StampOptions.Option chosen =
        PaymentOrderService.validChoice(OPTIONS, new CheckoutRequest(44_000L, null));

    assertThat(chosen.recommended()).isTrue();
  }

  @Test
  void anAcknowledgementOnAnAtOrAboveDutyChoiceIsIgnored() {
    assertThat(
            PaymentOrderService.validChoice(OPTIONS, new CheckoutRequest(44_000L, CURRENT))
                .belowDuty())
        .isFalse();
  }

  @Test
  void aMissingChoiceIsRefused() {
    assertRefused(null, Reason.CHOICE_REQUIRED);
    assertRefused(new CheckoutRequest(null, CURRENT), Reason.CHOICE_REQUIRED);
  }

  @Test
  void aValueThatIsNotAnOfferedOptionIsRefused() {
    assertRefused(new CheckoutRequest(30_000L, CURRENT), Reason.NOT_AN_OPTION);
    assertRefused(new CheckoutRequest(0L, null), Reason.NOT_AN_OPTION);
  }

  @Test
  void belowDutyWithoutAcknowledgementIsRefused() {
    assertRefused(new CheckoutRequest(10_000L, null), Reason.ACKNOWLEDGEMENT_REQUIRED);
    assertRefused(
        new CheckoutRequest(10_000L, new CheckoutRequest.Acknowledgement(" ")),
        Reason.ACKNOWLEDGEMENT_REQUIRED);
  }

  @Test
  void belowDutyAcknowledgedAgainstAStaleWarningIsRefused() {
    assertRefused(
        new CheckoutRequest(10_000L, new CheckoutRequest.Acknowledgement("under-stamp-v0")),
        Reason.WARNING_VERSION_STALE);
  }

  @Test
  void belowDutyWithTheCurrentAcknowledgementIsAccepted() {
    StampOptions.Option chosen =
        PaymentOrderService.validChoice(OPTIONS, new CheckoutRequest(10_000L, CURRENT));

    assertThat(chosen.belowDuty()).isTrue();
    assertThat(chosen.stampValuePaise()).isEqualTo(10_000L);
  }
}
