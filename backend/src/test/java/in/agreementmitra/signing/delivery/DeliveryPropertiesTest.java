package in.agreementmitra.signing.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The retry schedule: doubling backoff, bounded at both ends.
 *
 * <p>Bounded matters more than it looks. An unbounded schedule would hammer a struggling provider;
 * an unbounded attempt count would retry a permanently broken mailbox forever, which guarantees
 * nobody ever looks at it - and a document nobody looks at is a document that never arrives.
 */
class DeliveryPropertiesTest {

  private static DeliveryProperties properties() {
    return new DeliveryProperties(
        true, Duration.ofMinutes(5), 5, Duration.ofMinutes(1), Duration.ofHours(1), 50);
  }

  @Test
  void backoffDoublesFromTheInitialDelay() {
    DeliveryProperties properties = properties();
    assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
    assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofMinutes(4));
    assertThat(properties.backoffAfter(4)).isEqualTo(Duration.ofMinutes(8));
  }

  @Test
  void backoffIsCappedAndNeverNegativeOrZeroForTheFirstAttempt() {
    DeliveryProperties properties = properties();
    assertThat(properties.backoffAfter(20)).isEqualTo(Duration.ofHours(1));
    // Defensive: a nonsensical attempt number must not produce a negative shift or a huge delay.
    assertThat(properties.backoffAfter(0)).isEqualTo(Duration.ofMinutes(1));
    assertThat(properties.backoffAfter(-3)).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void absentSettingsFallBackToBoundedDefaultsRatherThanZeroOrInfinity() {
    DeliveryProperties defaults = new DeliveryProperties(true, null, 0, null, null, 0);
    assertThat(defaults.maxAttempts()).isEqualTo(5);
    assertThat(defaults.batchSize()).isEqualTo(50);
    assertThat(defaults.interval()).isEqualTo(Duration.ofMinutes(5));
    assertThat(defaults.initialBackoff()).isEqualTo(Duration.ofMinutes(1));
    assertThat(defaults.maxBackoff()).isEqualTo(Duration.ofHours(1));
  }
}
