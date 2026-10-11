package in.agreementmitra.signing.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentEventOutcomeTest {

  /** Adding a confirmation outcome without a ledger outcome fails here, not silently at a write. */
  @ParameterizedTest
  @EnumSource(ConfirmationOutcome.class)
  void everyConfirmationOutcomeMapsToTheLedgerOutcomeOfTheSameName(ConfirmationOutcome outcome) {
    assertThat(PaymentEventOutcome.of(outcome).name()).isEqualTo(outcome.name());
  }

  @Test
  void everyOutcomeAndSourceNameFitsItsColumn() {
    for (PaymentEventOutcome outcome : PaymentEventOutcome.values()) {
      assertThat(outcome.name().length()).isLessThanOrEqualTo(PaymentEvent.OUTCOME_WIDTH);
    }
    for (PaymentEventOrigin.Source source : PaymentEventOrigin.Source.values()) {
      assertThat(source.name().length()).isLessThanOrEqualTo(PaymentEvent.SOURCE_WIDTH);
    }
  }
}
