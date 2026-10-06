package in.agreementmitra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * A two-dimensional sliding-window rate limiter with an optional lockout, shared by every
 * per-source control in the application (anonymous-surface-abuse-controls D3). Generalised from the
 * recovery limiter, whose reasoning it keeps.
 *
 * <p><b>Two dimensions, because they stop different things.</b> Per-source stops one machine
 * sweeping many resources; per-resource stops many machines converging on one. A request refused by
 * the per-resource limit is still counted against its source, so tripping a resource refunds no
 * source budget; a request refused by its source is not counted against the resource, so a
 * locked-out caller cannot keep an agreement's bucket full and starve its other link holders.
 *
 * <p><b>A source is not a person.</b> A source is an IP, and an office, a housing society or any
 * carrier NAT puts many unrelated customers behind one address, so the per-source limit is set well
 * above what a household generates and what it bounds is a sweep. The per-resource limit of a route
 * class is set higher still (enforced by the configuration that feeds this), so one source exhausts
 * its own budget -- and stops being counted -- before it can exhaust an agreement's.
 *
 * <p><b>Keys are scoped (class, dimension, value)</b>, and a lockout is held per class: a source
 * locked out of one class is still served by every other, so one NAT neighbour tripping one limit
 * cannot lock everyone behind that address out of everything.
 *
 * <p><b>Bounded memory.</b> Entries live in a Caffeine cache that expires a key once its window and
 * lockout have both elapsed ({@code expireAfterAccess(window + lockout)}) and never holds more than
 * {@code maximumSize} keys. Expiry alone is not a bound: entries within one window grow with
 * request rate times distinct keys. Each per-key update runs inside the cache's atomic {@code
 * compute}, so an eviction racing an update cannot lose a hit or resurrect an entry. A refused
 * attempt is not added to its own window, so an entry never holds more than its limit's worth of
 * timestamps.
 *
 * <p><b>In-memory, and therefore per-instance.</b> On a single deployment that is the whole
 * population. On more than one it weakens proportionally rather than failing; the moment to move it
 * to shared storage is when the app is actually scaled out ({@code shared-limiter-store}).
 */
public final class SlidingWindowRateLimiter {

  /**
   * One dimension's allowance.
   *
   * @param maxRequests requests admitted per window
   * @param window the sliding window
   * @param lockout how long the key is refused once it exceeds the limit; {@link Duration#ZERO}
   *     refuses only while the window is full and never locks out
   */
  public record Limit(int maxRequests, Duration window, Duration lockout) {

    public Limit {
      if (maxRequests <= 0) {
        throw new IllegalArgumentException("maxRequests must be positive");
      }
      if (window == null || window.isZero() || window.isNegative()) {
        throw new IllegalArgumentException("window must be positive");
      }
      if (lockout == null || lockout.isNegative()) {
        throw new IllegalArgumentException("lockout must not be negative");
      }
    }

    boolean locksOut() {
      return !lockout.isZero();
    }
  }

  /** Which dimension of an attempt a verdict describes. */
  private enum Dimension {
    SOURCE,
    RESOURCE
  }

  /**
   * The verdict on one attempt.
   *
   * @param allowed true when every dimension admitted the attempt
   * @param retryAfter how long until a refused caller may succeed; zero when allowed
   * @param sourceLockout the attempts counted in the window that placed the source into lockout on
   *     THIS attempt, or 0 -- set only on the transition, never while an existing lockout refuses
   * @param resourceLockout the same for the resource dimension. Never set together with {@code
   *     sourceLockout}: a source lockout means the source refused, and a refused source is not
   *     counted against the resource.
   */
  public record Decision(
      boolean allowed, Duration retryAfter, int sourceLockout, int resourceLockout) {}

  private record Key(String routeClass, Dimension dimension, String value) {}

  private record Verdict(
      boolean allowed, long retryAfterMillis, boolean lockoutStarted, int count) {
    static final Verdict ALLOWED = new Verdict(true, 0, false, 0);
  }

  private static final class Entry {
    final Deque<Long> hits = new ArrayDeque<>();
    long lockedUntilMillis;
  }

  private final Cache<Key, Entry> entries;
  private final Clock clock;

  /**
   * @param retention how long a key may go untouched before it is dropped: the longest window plus
   *     the longest lockout this limiter is used with
   * @param maximumSize the hard ceiling on retained keys
   */
  public SlidingWindowRateLimiter(Duration retention, long maximumSize) {
    this(retention, maximumSize, Clock.systemUTC(), ForkJoinPool.commonPool());
  }

  /**
   * Test seam: a {@code clock} drives both the windows and the cache's expiry (its {@code Ticker}
   * is bridged from the clock), and a same-thread {@code executor} makes eviction deterministic.
   */
  SlidingWindowRateLimiter(Duration retention, long maximumSize, Clock clock, Executor executor) {
    this.clock = clock;
    this.entries =
        Caffeine.newBuilder()
            .expireAfterAccess(retention)
            .maximumSize(maximumSize)
            .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
            .executor(executor)
            .build();
  }

  /**
   * Record an attempt on both dimensions and report whether it may proceed.
   *
   * @param routeClass the class the budgets and lockouts are scoped to
   * @param source the source key; never null
   * @param sourceLimit the per-source allowance
   * @param resource the canonical resource key, or null for a source-only attempt
   * @param resourceLimit the per-resource allowance; ignored when {@code resource} is null
   */
  public Decision tryAcquire(
      String routeClass, String source, Limit sourceLimit, String resource, Limit resourceLimit) {
    long now = clock.millis();
    Verdict bySource = record(new Key(routeClass, Dimension.SOURCE, source), sourceLimit, now);
    // A request the source dimension refused never reaches the resource's budget: otherwise one
    // locked-out caller hammering an agreement would keep its bucket full and starve every other
    // holder of the link. The reverse still holds -- a resource refusal is counted against the
    // source -- so a sweep that trips a resource gets no source budget back.
    Verdict byResource =
        resource == null || resourceLimit == null || !bySource.allowed()
            ? Verdict.ALLOWED
            : record(new Key(routeClass, Dimension.RESOURCE, resource), resourceLimit, now);

    long retryAfter = Math.max(bySource.retryAfterMillis(), byResource.retryAfterMillis());
    return new Decision(
        bySource.allowed() && byResource.allowed(),
        Duration.ofMillis(retryAfter),
        bySource.lockoutStarted() ? bySource.count() : 0,
        byResource.lockoutStarted() ? byResource.count() : 0);
  }

  /** Forget every recorded attempt. For tests that need a clean window, never for production. */
  public void reset() {
    entries.invalidateAll();
    entries.cleanUp();
  }

  /** The number of keys currently retained. Package-private, for the eviction tests. */
  long retainedKeys() {
    entries.cleanUp();
    return entries.estimatedSize();
  }

  private Verdict record(Key key, Limit limit, long now) {
    Verdict[] verdict = new Verdict[1];
    entries
        .asMap()
        .compute(
            key,
            (k, existing) -> {
              Entry entry = existing == null ? new Entry() : existing;
              verdict[0] = apply(entry, limit, now);
              return entry;
            });
    return verdict[0];
  }

  private static Verdict apply(Entry entry, Limit limit, long now) {
    if (entry.lockedUntilMillis > now) {
      return new Verdict(false, entry.lockedUntilMillis - now, false, 0);
    }
    entry.lockedUntilMillis = 0;

    long windowMillis = limit.window().toMillis();
    long cutoff = now - windowMillis;
    while (!entry.hits.isEmpty() && entry.hits.peekFirst() <= cutoff) {
      entry.hits.removeFirst();
    }
    if (entry.hits.size() < limit.maxRequests()) {
      entry.hits.addLast(now);
      return Verdict.ALLOWED;
    }
    if (limit.locksOut()) {
      int count = entry.hits.size();
      entry.lockedUntilMillis = now + limit.lockout().toMillis();
      entry.hits.clear();
      return new Verdict(false, limit.lockout().toMillis(), true, count);
    }
    // No lockout: refuse until the oldest counted hit leaves the window.
    return new Verdict(false, entry.hits.peekFirst() + windowMillis - now, false, 0);
  }
}
