package in.agreementmitra.documents;

import in.agreementmitra.RenderCapacityException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Render admission control (anonymous-surface-abuse-controls D6): refuse, do not queue.
 *
 * <p>A blocking wait for a render slot lets a flood park request threads -- and, because the render
 * paths run inside a read-only transaction, Hikari connections -- until every DB-backed endpoint
 * stalls, not just rendering. So both how long and how many renders wait are bounded: a render that
 * finds every eligible slot busy joins a waiting room of at most {@code maxWaiters}, waits at most
 * {@code admissionWait}, and is refused with {@link RenderCapacityException} when either bound is
 * hit. An admitted render runs exactly as before.
 *
 * <p>The slots are split into a general pool and a reserved pool. A {@link RenderPriority#STANDARD}
 * render takes only a general slot; a {@link RenderPriority#FULFILMENT} render -- the paid e-stamp
 * render -- takes either, so an anonymous preview flood cannot fast-fail staff fulfilment of an
 * agreement a customer has already paid for. For the same reason a fulfilment render that has to
 * wait is not counted against (or refused by) the waiting room the anonymous renders fill.
 */
final class RenderAdmission {

  /**
   * How often a fulfilment waiter re-checks the general pool while it waits on the reserved one.
   */
  private static final long FULFILMENT_POLL_MILLIS = 50;

  /** A slot taken; release it exactly once, in a {@code finally}. */
  interface Slot extends AutoCloseable {
    @Override
    void close();
  }

  private final Semaphore general;
  private final Semaphore reserved;
  private final int maxWaiters;
  private final Duration admissionWait;
  private final AtomicInteger waiting = new AtomicInteger();

  RenderAdmission(int generalSlots, int reservedSlots, int maxWaiters, Duration admissionWait) {
    this.general = new Semaphore(generalSlots);
    this.reserved = new Semaphore(reservedSlots);
    this.maxWaiters = maxWaiters;
    this.admissionWait = admissionWait;
  }

  /**
   * Take a slot {@code priority} may use, or refuse.
   *
   * @throws RenderCapacityException when no eligible slot frees in time or the waiting room is full
   * @throws DocumentRenderException if the thread is interrupted while waiting
   */
  Slot admit(RenderPriority priority) {
    if (general.tryAcquire()) {
      return general::release;
    }
    if (priority == RenderPriority.FULFILMENT && reserved.tryAcquire()) {
      return reserved::release;
    }
    if (priority == RenderPriority.FULFILMENT) {
      // Not counted against the waiting room: anonymous waiters filling it must not fast-fail paid
      // fulfilment. Staff-driven and rare, so it cannot itself become a flood; it still waits only
      // admissionWait.
      return awaitOrRefuse(this::awaitEither);
    }
    if (waiting.incrementAndGet() > maxWaiters) {
      waiting.decrementAndGet();
      throw new RenderCapacityException("every render slot is busy and the waiting room is full");
    }
    try {
      return awaitOrRefuse(this::awaitGeneral);
    } finally {
      waiting.decrementAndGet();
    }
  }

  private interface Await {
    Slot await() throws InterruptedException;
  }

  private static Slot awaitOrRefuse(Await await) {
    try {
      Slot slot = await.await();
      if (slot == null) {
        throw new RenderCapacityException("no render slot freed within the admission wait");
      }
      return slot;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DocumentRenderException("render interrupted while waiting for a render slot", e);
    }
  }

  private Slot awaitGeneral() throws InterruptedException {
    return general.tryAcquire(admissionWait.toNanos(), TimeUnit.NANOSECONDS)
        ? general::release
        : null;
  }

  private Slot awaitEither() throws InterruptedException {
    long deadline = System.nanoTime() + admissionWait.toNanos();
    while (true) {
      if (general.tryAcquire()) {
        return general::release;
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return null;
      }
      long step = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(FULFILMENT_POLL_MILLIS));
      if (reserved.tryAcquire(step, TimeUnit.NANOSECONDS)) {
        return reserved::release;
      }
    }
  }
}
