package in.agreementmitra.signing.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Bounds how often recovery may be requested, per source and per reference.
 *
 * <p><b>This is defence in depth, not the control.</b> The control is that a reference returns
 * nothing and only causes mail to go to the legitimate parties (design D1), so enumeration is
 * pointless whatever the rate. What this bounds is the nuisance an attacker can generate: mail sent
 * to real people who did not ask for it.
 *
 * <p>Limits both dimensions because they stop different things. Per-source stops one machine
 * sweeping many references; per-reference stops many machines flooding one signer's inbox.
 *
 * <p><b>In-memory, and therefore per-instance.</b> On a single deployment that is the whole
 * population. On more than one it becomes per-instance, which weakens the bound proportionally
 * rather than removing it - acceptable for a nuisance control, and the point at which to move this
 * to shared storage is when the app is actually scaled out, not before.
 */
@Component
class RecoveryRateLimiter {

  /**
   * Per-source allowance, deliberately looser than per-reference.
   *
   * <p>A source is an IP, and an IP is not a person: an office, a housing society, or any carrier
   * NAT puts many unrelated customers behind one address. Tuned as tightly as the per-reference
   * limit, this would throttle a second real customer who did nothing wrong. What it needs to stop
   * is a sweep across many references, and 30 in a quarter-hour is far below the volume a sweep
   * needs while being far above what a household generates.
   */
  private static final int SOURCE_LIMIT = 30;

  /**
   * Per-reference allowance, deliberately tight.
   *
   * <p>This is the one that protects a signer's inbox: every accepted request sends mail to the
   * parties on that agreement, and nobody legitimately needs their own link re-sent more than a
   * handful of times in a quarter of an hour.
   */
  private static final int REFERENCE_LIMIT = 5;

  private static final Duration WINDOW = Duration.ofMinutes(15);

  /** How long a key stays locked out once it exceeds the limit. */
  private static final Duration LOCKOUT = Duration.ofMinutes(30);

  private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
  private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

  /**
   * Record an attempt and report whether it may proceed.
   *
   * @param source a coarse identifier for the requester
   * @param reference the normalised reference being asked about
   * @return true when the request may be acted on
   */
  boolean tryAcquire(String source, String reference, Instant now) {
    // Both must pass, and both are recorded even when the other already failed - otherwise an
    // attacker who trips one limit gets the other dimension's budget refunded.
    boolean sourceOk = allow("s:" + source, SOURCE_LIMIT, now);
    boolean referenceOk = allow("r:" + reference, REFERENCE_LIMIT, now);
    return sourceOk && referenceOk;
  }

  /**
   * Forget every recorded attempt. For tests that need a clean window, never for production use.
   */
  void clear() {
    hits.clear();
    lockedUntil.clear();
  }

  private boolean allow(String key, int limit, Instant now) {
    Instant until = lockedUntil.get(key);
    if (until != null) {
      if (now.isBefore(until)) {
        return false;
      }
      lockedUntil.remove(key);
    }

    Deque<Instant> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (window) {
      Instant cutoff = now.minus(WINDOW);
      while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
        window.removeFirst();
      }
      if (window.size() >= limit) {
        lockedUntil.put(key, now.plus(LOCKOUT));
        window.clear();
        return false;
      }
      window.addLast(now);
      return true;
    }
  }
}
