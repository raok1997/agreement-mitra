package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.signing.UnrecordedPayment.Reason;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StaffAlertKindTest {

  @Test
  void everyReasonRaisesItsOwnKind() {
    assertThat(Arrays.stream(Reason.values()).map(StaffAlertKind::of))
        .doesNotHaveDuplicates()
        .containsExactly(
            StaffAlertKind.PAYMENT_NOT_RECORDED,
            StaffAlertKind.PAYMENT_ID_REUSED,
            StaffAlertKind.PAYMENT_AMOUNT_MISMATCH);
  }

  /** {@code staff_alert.kind} is {@code VARCHAR(24)}. */
  @Test
  void everyKindNameFitsItsColumn() {
    assertThat(StaffAlertKind.values())
        .allSatisfy(kind -> assertThat(kind.name()).hasSizeLessThanOrEqualTo(24));
  }
}
