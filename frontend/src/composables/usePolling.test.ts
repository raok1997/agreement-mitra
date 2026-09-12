import { afterEach, describe, expect, it, vi } from "vitest";
import { usePolling, type PollVerdict } from "./usePolling";

// The loop's rules, tested with a hand-cranked clock: every `wait` is captured and released by the
// test, so nothing here depends on real time and every assertion is about ordering.
function harness() {
  const waits: Array<{ ms: number; release: () => void }> = [];
  let clock = 0;
  let hidden = false;
  const wait = (ms: number) =>
    new Promise<void>((release) => {
      waits.push({ ms, release });
    });
  const settle = async () => {
    // Let the loop run up to its next await.
    for (let i = 0; i < 4; i += 1) await Promise.resolve();
  };
  const release = async () => {
    const next = waits.shift();
    if (!next) throw new Error("nothing is waiting");
    clock += next.ms;
    next.release();
    await settle();
  };
  return {
    wait,
    release,
    settle,
    waits,
    now: () => clock,
    isHidden: () => hidden,
    setHidden: (v: boolean) => {
      hidden = v;
    },
    advance: (ms: number) => {
      clock += ms;
    },
  };
}

function reads(...verdicts: PollVerdict[]) {
  const fn = vi.fn<() => Promise<PollVerdict>>();
  for (const v of verdicts) fn.mockResolvedValueOnce(v);
  return fn.mockResolvedValue("continue");
}

describe("usePolling", () => {
  let stopper: (() => void) | null = null;
  afterEach(() => {
    stopper?.();
    stopper = null;
  });

  it("reads once per interval and keeps going on 'continue'", async () => {
    const h = harness();
    const read = reads();
    const p = usePolling(read, {
      intervalMs: 20,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    expect(read).not.toHaveBeenCalled(); // never immediate: the view has just loaded
    expect(h.waits[0]?.ms).toBe(20);
    await h.release();
    expect(read).toHaveBeenCalledTimes(1);
    await h.release();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it("stops for good on 'stop'", async () => {
    const h = harness();
    const read = reads("stop");
    const p = usePolling(read, {
      intervalMs: 20,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    await h.release();
    expect(read).toHaveBeenCalledTimes(1);
    expect(h.waits).toHaveLength(0);
  });

  it("backs off exponentially on error, capped, and recovers to the base on success", async () => {
    const h = harness();
    const read = vi
      .fn<() => Promise<PollVerdict>>()
      .mockRejectedValueOnce(new Error("boom"))
      .mockRejectedValueOnce(new Error("boom"))
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValue("continue");
    const p = usePolling(read, {
      intervalMs: 20,
      maxIntervalMs: 50,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    await h.release(); // error 1 -> next wait 40
    expect(h.waits.at(-1)?.ms).toBe(40);
    await h.release(); // error 2 -> 80 capped to 50
    expect(h.waits.at(-1)?.ms).toBe(50);
    await h.release(); // error 3 -> stays 50
    expect(h.waits.at(-1)?.ms).toBe(50);
    await h.release(); // success -> back to base
    expect(h.waits.at(-1)?.ms).toBe(20);
  });

  it("slows to the cap on 'slow'", async () => {
    const h = harness();
    const p = usePolling(reads("slow"), {
      intervalMs: 20,
      maxIntervalMs: 300,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    await h.release();
    expect(h.waits.at(-1)?.ms).toBe(300);
  });

  it("does not read while hidden, and a resume honours the floor", async () => {
    const h = harness();
    const read = reads();
    const p = usePolling(read, {
      intervalMs: 20,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    await h.release(); // read 1 at t=20
    expect(read).toHaveBeenCalledTimes(1);

    h.setHidden(true);
    await h.release(); // interval elapses at t=40, but the tab is hidden: no read
    expect(read).toHaveBeenCalledTimes(1);

    // Becomes visible only 5 ms after the last read: the floor holds.
    h.advance(-15); // t=25, i.e. 5 ms since the read at t=20
    h.setHidden(false);
    document.dispatchEvent(new Event("visibilitychange"));
    await h.settle();
    expect(read).toHaveBeenCalledTimes(1);
    expect(h.waits.at(-1)?.ms).toBe(15); // waits out the remainder of the floor
    await h.release();
    expect(read).toHaveBeenCalledTimes(2);
  });

  it("stop() releases a sleeping loop, and a later start() runs exactly one loop", async () => {
    const h = harness();
    const read = reads();
    const p = usePolling(read, {
      intervalMs: 20,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    await h.release(); // read 1; loop is now sleeping on wait #2
    expect(read).toHaveBeenCalledTimes(1);

    p.stop();
    await h.settle();
    p.start();
    await h.settle();
    // The abandoned wait and the new loop's wait are both outstanding; firing the abandoned one
    // must not produce a read, only the new loop's.
    expect(h.waits).toHaveLength(2);
    await h.release(); // abandoned
    expect(read).toHaveBeenCalledTimes(1);
    await h.release(); // the live loop
    expect(read).toHaveBeenCalledTimes(2);
    expect(h.waits).toHaveLength(1);
  });

  it("start() is idempotent while running", async () => {
    const h = harness();
    const read = reads();
    const p = usePolling(read, {
      intervalMs: 20,
      wait: h.wait,
      now: h.now,
      isHidden: h.isHidden,
    });
    stopper = p.stop;

    p.start();
    p.start();
    expect(h.waits).toHaveLength(1);
  });

  it("stop() is idempotent and safe before start", () => {
    const h = harness();
    const p = usePolling(async () => "continue", {
      wait: h.wait,
      isHidden: h.isHidden,
    });
    p.stop();
    p.stop();
    expect(h.waits).toHaveLength(0);
  });
});
