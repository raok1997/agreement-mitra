package in.agreementmitra.signing.recovery;

import in.agreementmitra.SlidingWindowRateLimiter;
import in.agreementmitra.SlidingWindowRateLimiter.Limit;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Recovery's allowance on the shared {@link SlidingWindowRateLimiter}.
 *
 * <p><b>This is defence in depth, not the control.</b> The control is that a reference returns
 * nothing and only causes mail to go to the legitimate parties (design D1), so enumeration is
 * pointless whatever the rate. What this bounds is the nuisance an attacker can generate: mail sent
 * to real people who did not ask for it.
 *
 * <p>Both dimensions lock out, unlike the route classes: recovery's answer is a uniform
 * fire-and-forget {@code 202}, so a locked reference denies no one anything.
 */
@Configuration(proxyBeanMethods = false)
class RecoveryLimiterConfig {

  /** The bean name, for the tests that reset it between cases. */
  static final String LIMITER = "recoveryRateLimiter";

  /**
   * Per-source allowance, deliberately looser than per-reference.
   *
   * <p>A source is an IP, and an IP is not a person: an office, a housing society, or any carrier
   * NAT puts many unrelated customers behind one address. Tuned as tightly as the per-reference
   * limit, this would throttle a second real customer who did nothing wrong. What it needs to stop
   * is a sweep across many references, and 30 in a quarter-hour is far below the volume a sweep
   * needs while being far above what a household generates.
   */
  static final Limit SOURCE = new Limit(30, Duration.ofMinutes(15), Duration.ofMinutes(30));

  /**
   * Per-reference allowance, deliberately tight.
   *
   * <p>This is the one that protects a signer's inbox: every accepted request sends mail to the
   * parties on that agreement, and nobody legitimately needs their own link re-sent more than a
   * handful of times in a quarter of an hour.
   */
  static final Limit REFERENCE = new Limit(5, Duration.ofMinutes(15), Duration.ofMinutes(30));

  /** Ceiling on retained keys (sources plus references); a few MB at most. */
  private static final long MAXIMUM_SIZE = 100_000;

  @Bean(LIMITER)
  SlidingWindowRateLimiter recoveryRateLimiter() {
    return new SlidingWindowRateLimiter(SOURCE.window().plus(SOURCE.lockout()), MAXIMUM_SIZE);
  }
}
