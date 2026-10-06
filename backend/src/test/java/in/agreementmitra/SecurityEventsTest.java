package in.agreementmitra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Redaction and bounded emission of security events (anonymous-surface-abuse-controls 10.9). */
class SecurityEventsTest {

  /** Any UUID-shaped substring, in any case. */
  static final Pattern UUID_SHAPED =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final String ID = "3f2c1b9e-8d4a-4c1e-9f0a-1b2c3d4e5f60";

  private final SlidingWindowRateLimiterTest.TestClock clock =
      new SlidingWindowRateLimiterTest.TestClock();
  private final SecurityEvents events = new SecurityEvents(clock, Runnable::run);
  private final ClientSource source = new ClientSourceResolver(64).resolve("203.0.113.77");

  @Test
  void aSourceIsRedactedToItsPrefix() {
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      events.lockout("/api/agreements/{id}/finalise", "capability-write", source, 30);
      assertThat(capture.lines())
          .singleElement()
          .asString()
          .contains("event=rate_limit_lockout")
          .contains("route=/api/agreements/{id}/finalise")
          .contains("class=capability-write")
          .contains("source=203.0.113.0/24")
          .contains("count=30")
          .doesNotContain("203.0.113.77");
    }
  }

  @Test
  void aPerResourceLockoutCarriesTheResourceOnlyAsAKeyedDigest() {
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      events.resourceLockout("/api/agreements/recovery", "recovery", source, ID, 5);
      String line = capture.lines().get(0);
      assertThat(line).doesNotContain(ID).contains("resource=" + events.digest(ID));
      assertThat(UUID_SHAPED.matcher(line).find()).isFalse();
      // Keyed: another process (a fresh random key) gives a different digest.
      assertThat(new SecurityEvents(clock, Runnable::run).digest(ID))
          .isNotEqualTo(events.digest(ID));
    }
  }

  @Test
  void repeatedVerificationFailuresEmitTheFirstAtOnceThenOnePerMinuteWithACount() {
    try (SecurityEventCapture capture = new SecurityEventCapture()) {
      events.webhookVerificationFailed("/api/webhooks/razorpay", source);
      for (int i = 0; i < 9; i++) {
        clock.advance(Duration.ofSeconds(5));
        events.webhookVerificationFailed("/api/webhooks/razorpay", source);
      }
      assertThat(capture.lines()).hasSize(1);
      assertThat(capture.lines().get(0)).contains("count=1");

      clock.advance(Duration.ofSeconds(20)); // 65 s after the first event
      events.webhookVerificationFailed("/api/webhooks/razorpay", source);
      assertThat(capture.lines()).hasSize(2);
      assertThat(capture.lines().get(1))
          .contains("event=webhook_verification_failed")
          .contains("route=/api/webhooks/razorpay")
          .contains("source=203.0.113.0/24")
          .contains("count=10");
    }
  }
}
