import { getCurrentScope, onScopeDispose } from "vue";

// A re-read loop for a page that has to stay current while something happens elsewhere (staff
// stamping, parties signing). The rules live here, not in the view, so they can be tested without a
// DOM or a real clock: a floor so an open tab is a courtesy load and not a hammer, backoff on error
// with a cap, no reads while the tab is hidden, and a resume on becoming visible that still honours
// the floor. Every timer is injectable; no test waits on real time.

/** What one read decided about the next: keep going, slow to the cap, or stop for good. */
export type PollVerdict = "continue" | "slow" | "stop";

export interface PollingOptions {
  /** Base interval between reads. Default 20 s -- the floor. */
  intervalMs?: number;
  /** Longest interval backoff (or a "slow" verdict) can reach. Default 5 min. */
  maxIntervalMs?: number;
  /** Injectable for tests. */
  wait?: (ms: number) => Promise<void>;
  now?: () => number;
  isHidden?: () => boolean;
}

export interface Polling {
  /** Begin the loop; the first read happens after one interval, not immediately. Idempotent. */
  start(): void;
  /** Stop for good. Safe to call twice, before start, and before a later start. */
  stop(): void;
}

const DEFAULT_INTERVAL_MS = 20_000;
const DEFAULT_MAX_INTERVAL_MS = 300_000;

const realWait = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

export function usePolling(
  read: () => Promise<PollVerdict>,
  options: PollingOptions = {},
): Polling {
  const base = options.intervalMs ?? DEFAULT_INTERVAL_MS;
  const max = options.maxIntervalMs ?? DEFAULT_MAX_INTERVAL_MS;
  const wait = options.wait ?? realWait;
  const now = options.now ?? (() => Date.now());
  const isHidden =
    options.isHidden ??
    (() =>
      typeof document !== "undefined" && document.visibilityState === "hidden");

  // Each start() is a generation; every await in the loop re-checks it, so a stop() followed by a
  // start() while the old loop is still sleeping cannot leave two loops reading. stop() also
  // releases whatever the loop is waiting on, so the closure dies now rather than when a timer
  // that nobody can cancel finally fires.
  let generation = 0;
  let release: (() => void) | null = null;
  let resumeWhenVisible: (() => void) | null = null;

  function onVisibilityChange(): void {
    if (!isHidden() && resumeWhenVisible) {
      const resume = resumeWhenVisible;
      resumeWhenVisible = null;
      resume();
    }
  }

  async function loop(gen: number): Promise<void> {
    const alive = () => gen === generation;
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    const sleep = (ms: number) => Promise.race([wait(ms), released]);

    let delay = base;
    let lastReadAt = now();
    while (alive()) {
      await sleep(delay);
      if (!alive()) return;
      if (isHidden()) {
        // Sleep until the tab is visible again -- no reads while nobody is looking.
        await Promise.race([
          new Promise<void>((resolve) => {
            resumeWhenVisible = resolve;
          }),
          released,
        ]);
        if (!alive()) return;
        // Resumed: a tab toggle must not become a way past the floor.
        const since = now() - lastReadAt;
        if (since < base) {
          await sleep(base - since);
          if (!alive()) return;
        }
      }
      lastReadAt = now();
      try {
        const verdict = await read();
        if (!alive()) return;
        if (verdict === "stop") {
          stop();
          return;
        }
        delay = verdict === "slow" ? max : base;
      } catch {
        delay = Math.min(delay * 2, max);
      }
    }
  }

  function start(): void {
    if (release) return; // already running
    generation += 1;
    if (typeof document !== "undefined") {
      document.addEventListener("visibilitychange", onVisibilityChange);
    }
    void loop(generation);
  }

  function stop(): void {
    generation += 1;
    if (typeof document !== "undefined") {
      document.removeEventListener("visibilitychange", onVisibilityChange);
    }
    resumeWhenVisible = null;
    const r = release;
    release = null;
    r?.();
  }

  // Inside a component this dies with it; outside (a unit test) the caller stops it.
  if (getCurrentScope()) onScopeDispose(stop);

  return { start, stop };
}
