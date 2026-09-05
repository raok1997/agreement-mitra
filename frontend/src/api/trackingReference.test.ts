import { describe, expect, it } from "vitest";
import { isWellFormedReference, normalizeReference } from "./trackingReference";

// Why this validation exists at all: the recovery endpoint answers identically whatever you send
// it, so a mistyped reference produces a cheerful "check your email" and an inbox that stays empty
// forever. Catching the typo locally is the only way to turn that dead end into a correction -- and
// it does so without asking the server anything, so it leaks nothing about which references exist.
describe("tracking reference validation", () => {
  const VALID = "AM3G3VXSAKD";

  it("accepts a reference the server issued", () => {
    expect(isWellFormedReference(VALID)).toBe(true);
  });

  it("normalizes case and the spacing people add when reading a code aloud", () => {
    expect(normalizeReference(" am3g3-vxs akd ")).toBe(VALID);
    expect(isWellFormedReference(" am3g3-vxs akd ")).toBe(true);
  });

  it("rejects a single mistyped character", () => {
    // The check character exists for exactly this: a substitution is caught rather than sending
    // mail about a different agreement, or about none.
    const mistyped = "AM3G3VXSAKE";
    expect(mistyped).not.toBe(VALID);
    expect(isWellFormedReference(mistyped)).toBe(false);
  });

  it("rejects a transposition of two characters", () => {
    expect(isWellFormedReference("AM3G3VXASKD")).toBe(false);
  });

  it("rejects the wrong length", () => {
    expect(isWellFormedReference("AM3G3VXSAK")).toBe(false);
    expect(isWellFormedReference("AM3G3VXSAKDD")).toBe(false);
    expect(isWellFormedReference("")).toBe(false);
  });

  it("rejects a missing prefix", () => {
    expect(isWellFormedReference("XX3G3VXSAKD")).toBe(false);
  });

  it("rejects the confusable glyphs the alphabet deliberately excludes", () => {
    // 0/O and 1/I/L are not in the alphabet, so a reference containing one was misread.
    expect(isWellFormedReference("AM0G3VXSAKD")).toBe(false);
    expect(isWellFormedReference("AMIG3VXSAKD")).toBe(false);
  });
});
