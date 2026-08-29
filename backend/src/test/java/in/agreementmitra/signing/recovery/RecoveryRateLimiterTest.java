package in.agreementmitra.signing.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The nuisance bound on the recovery endpoint.
 *
 * <p>This is not what stops enumeration - uniform responses are (design D1), and they hold at any
 * rate. What this bounds is how much unsolicited mail an attacker can cause to be sent to real
 * signers. So the properties worth pinning are that both dimensions are limited independently, and
 * that tripping one does not refund the other's budget.
 */
class RecoveryRateLimiterTest {

  private static final Instant T0 = Instant.parse("2026-08-21T10:00:00Z");
  private static final String SOURCE = "203.0.113.7";
  private static final String REFERENCE = "AM3G3VXSAKD";

  /** The per-source allowance. Looser than per-reference because an IP is not a person. */
  private static final int SOURCE_LIMIT = 30;

  @Test
  void allowsRequestsUpToTheLimit() {
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();

    for (int i = 0; i < 5; i++) {
      assertThat(limiter.tryAcquire(SOURCE, REFERENCE + i, T0.plusSeconds(i)))
          .as("request %s within the limit", i)
          .isTrue();
    }
  }

  @Test
  void refusesOnceTheSourceExceedsTheLimit() {
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();
    // Distinct references each time, so it is the per-source dimension being exhausted and not the
    // per-reference one - a sweep across many references is exactly what this limit exists for.
    for (int i = 0; i < SOURCE_LIMIT; i++) {
      limiter.tryAcquire(SOURCE, "AM" + i + "AAAAAAAA", T0.plusSeconds(i));
    }

    assertThat(limiter.tryAcquire(SOURCE, "AMZZZZZZZZZ", T0.plusSeconds(SOURCE_LIMIT + 1)))
        .isFalse();
  }

  @Test
  void aSecondCustomerBehindTheSameAddressIsNotThrottledByAHandfulOfRequests() {
    // Offices and carrier NAT put unrelated customers behind one IP. A per-source limit tuned as
    // tightly as the per-reference one would refuse someone who did nothing wrong.
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();
    for (int i = 0; i < 6; i++) {
      limiter.tryAcquire(SOURCE, "AM" + i + "AAAAAAAA", T0.plusSeconds(i));
    }

    assertThat(limiter.tryAcquire(SOURCE, "AMYYYYYYYYY", T0.plusSeconds(7))).isTrue();
  }

  @Test
  void refusesOnceOneReferenceIsAskedAboutTooOften() {
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();
    for (int i = 0; i < 5; i++) {
      limiter.tryAcquire("source-" + i, REFERENCE, T0.plusSeconds(i));
    }

    // Many machines flooding one signer's inbox is stopped by the per-reference dimension - the
    // per-source limit alone would not catch this.
    assertThat(limiter.tryAcquire("source-fresh", REFERENCE, T0.plusSeconds(6))).isFalse();
  }

  @Test
  void aLockoutExpiresRatherThanBanningForever() {
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();
    for (int i = 0; i < 6; i++) {
      limiter.tryAcquire("source-" + i, REFERENCE, T0.plusSeconds(i));
    }
    assertThat(limiter.tryAcquire("source-fresh", REFERENCE, T0.plusSeconds(7))).isFalse();

    // A customer who asked a few times too often must not be shut out for good.
    Instant afterLockout = T0.plus(Duration.ofMinutes(31));
    assertThat(limiter.tryAcquire("source-fresh", REFERENCE, afterLockout)).isTrue();
  }

  @Test
  void requestsOutsideTheWindowDoNotCountAgainstTheLimit() {
    RecoveryRateLimiter limiter = new RecoveryRateLimiter();
    for (int i = 0; i < 4; i++) {
      limiter.tryAcquire("source-" + i, REFERENCE, T0.plusSeconds(i));
    }

    Instant muchLater = T0.plus(Duration.ofMinutes(20));
    assertThat(limiter.tryAcquire("source-late", REFERENCE, muchLater)).isTrue();
  }
}
