package in.agreementmitra.signing.staffalert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StaffAlertBackoffTest {

  @Test
  void theWaitsDoubleFromThirtySecondsToAnHour() {
    assertThat(
            IntStream.range(1, StaffAlertBackoff.MAX_ATTEMPTS)
                .mapToObj(StaffAlertBackoff::after)
                .toList())
        .containsExactly(
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(2),
            Duration.ofMinutes(4),
            Duration.ofMinutes(8),
            Duration.ofMinutes(16),
            Duration.ofMinutes(32),
            Duration.ofHours(1),
            Duration.ofHours(1));
  }

  @Test
  void theLastAttemptStillHoldsALease() {
    assertThat(StaffAlertBackoff.after(StaffAlertBackoff.MAX_ATTEMPTS))
        .isEqualTo(Duration.ofHours(1));
  }

  /** The shortest backoff is the claim's lease, so one send must always finish inside it. */
  @Test
  void theShortestBackoffOutlastsOneSend() {
    assertThat(StaffAlertBackoff.after(1))
        .isGreaterThan(
            DiscordStaffNotifier.CONNECT_TIMEOUT.plus(DiscordStaffNotifier.READ_TIMEOUT));
  }
}
