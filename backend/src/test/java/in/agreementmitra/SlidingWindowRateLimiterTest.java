package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import in.agreementmitra.SlidingWindowRateLimiter.Decision;
import in.agreementmitra.SlidingWindowRateLimiter.Limit;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared limiter (anonymous-surface-abuse-controls tasks 10.1, 10.2). */
class SlidingWindowRateLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);
  private static final Limit SOURCE = new Limit(3, WINDOW, Duration.ofMinutes(5));
  private static final Limit RESOURCE = new Limit(2, WINDOW, Duration.ZERO);

  /** A clock the test moves by hand; drives the windows and, through the Ticker, cache expiry. */
  static final class TestClock extends Clock {
    private Instant now = Instant.parse("2026-10-04T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final TestClock clock = new TestClock();

  private SlidingWindowRateLimiter limiter(long maximumSize) {
    return new SlidingWindowRateLimiter(
        WINDOW.plus(SOURCE.lockout()), maximumSize, clock, Runnable::run);
  }

  private static Decision sourceOnly(SlidingWindowRateLimiter limiter, String source) {
    return limiter.tryAcquire("render", source, SOURCE, null, null);
  }

  @Test
  void theLimitRefusesAndTheLockoutStartsOnTheTransitionOnly() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 3; i++) {
      assertThat(sourceOnly(limiter, "203.0.113.1").allowed()).isTrue();
    }
    Decision tripped = sourceOnly(limiter, "203.0.113.1");
    assertThat(tripped.allowed()).isFalse();
    assertThat(tripped.sourceLockout()).isEqualTo(3);
    assertThat(tripped.resourceLockout()).isZero();
    assertThat(tripped.retryAfter()).isEqualTo(Duration.ofMinutes(5));

    Decision stillLocked = sourceOnly(limiter, "203.0.113.1");
    assertThat(stillLocked.allowed()).isFalse();
    assertThat(stillLocked.sourceLockout()).isZero();
  }

  @Test
  void theWindowSlides() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit generous = new Limit(2, WINDOW, Duration.ZERO);
    assertThat(limiter.tryAcquire("c", "s", generous, null, null).allowed()).isTrue();
    clock.advance(Duration.ofSeconds(40));
    assertThat(limiter.tryAcquire("c", "s", generous, null, null).allowed()).isTrue();
    assertThat(limiter.tryAcquire("c", "s", generous, null, null).allowed()).isFalse();
    // The first hit leaves the window at +60s; the second is still inside it.
    clock.advance(Duration.ofSeconds(21));
    assertThat(limiter.tryAcquire("c", "s", generous, null, null).allowed()).isTrue();
    assertThat(limiter.tryAcquire("c", "s", generous, null, null).allowed()).isFalse();
  }

  @Test
  void aPerSourceLockoutExpires() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 4; i++) {
      sourceOnly(limiter, "s");
    }
    clock.advance(Duration.ofMinutes(4));
    assertThat(sourceOnly(limiter, "s").allowed()).isFalse();
    clock.advance(Duration.ofMinutes(1));
    assertThat(sourceOnly(limiter, "s").allowed()).isTrue();
  }

  @Test
  void aPerResourceRefusalSetsNoLockoutAndClearsOnceTheWindowMoves() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit roomySource = new Limit(100, WINDOW, Duration.ofMinutes(5));
    for (int i = 0; i < 2; i++) {
      assertThat(limiter.tryAcquire("read", "s" + i, roomySource, "id", RESOURCE).allowed())
          .isTrue();
    }
    Decision refused = limiter.tryAcquire("read", "s9", roomySource, "id", RESOURCE);
    assertThat(refused.allowed()).isFalse();
    assertThat(refused.sourceLockout()).isZero();
    assertThat(refused.resourceLockout()).isZero();
    assertThat(refused.retryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);

    clock.advance(WINDOW);
    assertThat(limiter.tryAcquire("read", "s9", roomySource, "id", RESOURCE).allowed()).isTrue();
  }

  @Test
  void aResourceRefusalStillCountsAgainstTheSource() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit roomySource = new Limit(4, WINDOW, Duration.ZERO);
    limiter.tryAcquire("read", "a", roomySource, "id", RESOURCE);
    limiter.tryAcquire("read", "a", roomySource, "id", RESOURCE);
    // Refused by the resource (2 already) -- but it still spends the source's budget.
    assertThat(limiter.tryAcquire("read", "a", roomySource, "id", RESOURCE).allowed()).isFalse();
    assertThat(limiter.tryAcquire("read", "a", roomySource, "other", RESOURCE).allowed()).isTrue();
    assertThat(limiter.tryAcquire("read", "a", roomySource, "third", RESOURCE).allowed()).isFalse();
  }

  @Test
  void oneSourceAtItsOwnLimitCannotExhaustAnAgreement() {
    // The shipped shape: per-resource above per-source (here 3 vs 2).
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit perSource = new Limit(2, WINDOW, Duration.ofMinutes(5));
    Limit perResource = new Limit(3, WINDOW, Duration.ZERO);
    for (int i = 0; i < 40; i++) {
      limiter.tryAcquire("read", "greedy", perSource, "id", perResource);
    }
    // The greedy holder spent two of the agreement's three, then locked itself out.
    assertThat(limiter.tryAcquire("read", "other", perSource, "id", perResource).allowed())
        .isTrue();
  }

  @Test
  void aRequestTheSourceRefusesDoesNotSpendTheResourcesBudget() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit tightSource = new Limit(1, WINDOW, Duration.ofMinutes(5));
    Limit roomySource = new Limit(100, WINDOW, Duration.ZERO);
    assertThat(limiter.tryAcquire("read", "a", tightSource, "id", RESOURCE).allowed()).isTrue();
    // A locked-out caller hammering the agreement...
    for (int i = 0; i < 50; i++) {
      assertThat(limiter.tryAcquire("read", "a", tightSource, "id", RESOURCE).allowed()).isFalse();
    }
    // ...leaves its bucket with room for the other link holder (budget 2, one used).
    assertThat(limiter.tryAcquire("read", "b", roomySource, "id", RESOURCE).allowed()).isTrue();
  }

  @Test
  void aLockoutInOneClassDoesNotRefuseTheSameSourceInAnother() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 4; i++) {
      limiter.tryAcquire("render", "s", SOURCE, null, null);
    }
    assertThat(limiter.tryAcquire("render", "s", SOURCE, null, null).allowed()).isFalse();
    assertThat(limiter.tryAcquire("capability-read", "s", SOURCE, null, null).allowed()).isTrue();
  }

  @Test
  void anUnrelatedKeyIsUnaffected() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 4; i++) {
      sourceOnly(limiter, "203.0.113.1");
    }
    assertThat(sourceOnly(limiter, "203.0.113.2").allowed()).isTrue();
  }

  @Test
  void anEntryWhoseWindowAndLockoutHaveElapsedIsDropped() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 4; i++) {
      sourceOnly(limiter, "s");
    }
    assertThat(limiter.retainedKeys()).isEqualTo(1);
    clock.advance(WINDOW.plus(SOURCE.lockout()).plusSeconds(1));
    assertThat(limiter.retainedKeys()).isZero();
  }

  @Test
  void distinctKeysBeyondTheCapLeaveTheRetainedCountAtOrBelowIt() {
    SlidingWindowRateLimiter limiter = limiter(50);
    for (int i = 0; i < 5_000; i++) {
      sourceOnly(limiter, "198.51.100." + i);
    }
    assertThat(limiter.retainedKeys()).isLessThanOrEqualTo(50);
  }

  @Test
  void aResourceLockoutIsReportedWhileTheSourceIsAdmitted() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    Limit roomySource = new Limit(100, WINDOW, Duration.ofMinutes(5));
    Limit lockingResource = new Limit(3, WINDOW, Duration.ofMinutes(5));
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("recovery", "s" + i, roomySource, "ref", lockingResource);
    }
    Decision locked = limiter.tryAcquire("recovery", "s9", roomySource, "ref", lockingResource);
    assertThat(locked.allowed()).isFalse();
    assertThat(locked.sourceLockout()).isZero();
    assertThat(locked.resourceLockout()).isEqualTo(3);
  }

  @Test
  void resetForgetsEverything() {
    SlidingWindowRateLimiter limiter = limiter(1000);
    for (int i = 0; i < 4; i++) {
      sourceOnly(limiter, "s");
    }
    limiter.reset();
    assertThat(sourceOnly(limiter, "s").allowed()).isTrue();
  }
}
