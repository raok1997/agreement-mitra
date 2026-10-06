package in.agreementmitra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Redacted security-event logging (anonymous-surface-abuse-controls D8): names the abuse, never the
 * credential.
 *
 * <p>Every event goes to the dedicated {@value #LOGGER} logger and carries only:
 *
 * <ul>
 *   <li>{@code event} -- {@code rate_limit_lockout} or {@code webhook_verification_failed};
 *   <li>{@code route} -- a route <b>pattern</b> from a fixed table (or the literal {@code
 *       default}), never the request URI, which carries the agreement id;
 *   <li>{@code class} -- the route class (lockouts only);
 *   <li>{@code source} -- the {@link ClientSource#redacted() redacted} prefix, never the address;
 *   <li>{@code count};
 *   <li>{@code resource} -- only on a per-resource lockout: an HMAC-SHA-256 of the canonical value,
 *       truncated, under a random key generated at startup and held only in memory. Events
 *       correlate within one process lifetime; there is no secret to configure or rotate, and a
 *       stolen log cannot be brute-forced back to the value.
 * </ul>
 *
 * <p>The agreement id is a bearer capability: an attacker reading a log would gain the access the
 * log was written to protect. No webhook payload is ever passed in, so none can be echoed.
 *
 * <p><b>Emission is bounded</b>, so logging cannot become the disk-fill vector the limits close. A
 * lockout is logged on the transition into it, never per refused request (the limiter reports only
 * the transition). A verification failure from a source is logged at once; later failures from it
 * are counted and the next one after {@link #VERIFICATION_INTERVAL} logs one event carrying that
 * count. A trailing count with no later failure is never logged -- accepted, since the first event
 * already flagged the source. That per-source state is a size-capped cache, so rotating sources
 * cannot grow it without bound.
 */
@Component
public class SecurityEvents {

  static final String LOGGER = "in.agreementmitra.security";

  static final Duration VERIFICATION_INTERVAL = Duration.ofMinutes(1);

  private static final int DIGEST_HEX_CHARS = 16;
  private static final long MAX_TRACKED_SOURCES = 10_000;

  private static final Logger log = LoggerFactory.getLogger(LOGGER);

  private static final class FailureWindow {
    long lastEmittedMillis;
    int suppressed;
  }

  private final byte[] hashKey = new byte[32];
  private final Clock clock;
  private final Cache<String, FailureWindow> verificationFailures;

  @Autowired
  public SecurityEvents() {
    this(Clock.systemUTC(), ForkJoinPool.commonPool());
  }

  SecurityEvents(Clock clock, Executor executor) {
    new SecureRandom().nextBytes(hashKey);
    this.clock = clock;
    this.verificationFailures =
        Caffeine.newBuilder()
            .expireAfterAccess(VERIFICATION_INTERVAL.multipliedBy(2))
            .maximumSize(MAX_TRACKED_SOURCES)
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
            .executor(executor)
            .build();
  }

  /**
   * A source-dimension lockout began on {@code route} (a classifier pattern or {@code default}).
   */
  public void lockout(String route, String routeClass, ClientSource source, int count) {
    log.warn(
        "event=rate_limit_lockout route={} class={} source={} count={}",
        route,
        routeClass,
        source.redacted(),
        count);
  }

  /** A resource-dimension lockout began; the resource is logged only as a keyed digest. */
  public void resourceLockout(
      String route, String routeClass, ClientSource source, String resource, int count) {
    log.warn(
        "event=rate_limit_lockout route={} class={} source={} resource={} count={}",
        route,
        routeClass,
        source.redacted(),
        digest(resource),
        count);
  }

  /**
   * An inbound webhook on {@code route} (the controller's own mapping) failed verification. Logged
   * at once for a source's first failure, then at most once per interval with a count.
   */
  public void webhookVerificationFailed(String route, ClientSource source) {
    long now = clock.millis();
    int[] toEmit = {-1};
    verificationFailures
        .asMap()
        .compute(
            route + '|' + source.key(),
            (k, existing) -> {
              if (existing == null) {
                FailureWindow fresh = new FailureWindow();
                fresh.lastEmittedMillis = now;
                toEmit[0] = 1;
                return fresh;
              }
              existing.suppressed++;
              if (now - existing.lastEmittedMillis >= VERIFICATION_INTERVAL.toMillis()) {
                toEmit[0] = existing.suppressed;
                existing.suppressed = 0;
                existing.lastEmittedMillis = now;
              }
              return existing;
            });
    if (toEmit[0] > 0) {
      log.warn(
          "event=webhook_verification_failed route={} source={} count={}",
          route,
          source.redacted(),
          toEmit[0]);
    }
  }

  /** The truncated keyed digest of {@code value}. Package-private for the redaction tests. */
  String digest(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
      byte[] full = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(full).substring(0, DIGEST_HEX_CHARS);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is a required JCA algorithm", e);
    }
  }
}
