import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { RELEASE_STATUS } from "./releaseStatus";

// Mirrors the backend's paid-fulfilment gate: the rules module will not sell stamping for a state
// whose rule carries no counsel review, so the board must not claim stamping is live there either.
const RULES_DIR = resolve(
  process.cwd(),
  "../backend/src/main/resources/rules/stamp-duty",
);

function ruleFiles(state: string): string[] {
  const dir = resolve(RULES_DIR, state);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".yaml"))
    .map((f) => resolve(dir, f));
}

// A reviewed rule records `counselReview: { contentHash: ... }` (flow or block mapping). Anything
// else -- null, ~, a bare key, a mapping without a hash -- is unreviewed. Whether the hash still
// matches the figures is the backend's check (the rules module), deliberately not duplicated here.
function hasCounselReview(yaml: string): boolean {
  const lines = yaml.split(/\r?\n/);
  const i = lines.findIndex((l) => /^counselReview:/.test(l));
  if (i < 0) return false;
  const inline = lines[i]
    .replace(/^counselReview:/, "")
    .replace(/#.*$/, "")
    .trim();
  if (inline.startsWith("{"))
    return /contentHash:\s*["']?[^\s"',}]+/.test(inline);
  if (inline !== "") return false;
  const block: string[] = [];
  for (const l of lines.slice(i + 1)) {
    if (!/^\s+\S/.test(l)) break;
    block.push(l);
  }
  return block.some((l) => /^\s+contentHash:\s*["']?[^\s"']+/.test(l));
}

describe("hasCounselReview", () => {
  it("accepts a review carrying a content hash, in flow or block form", () => {
    expect(
      hasCounselReview('counselReview: { reviewer: c, contentHash: "ab12" }'),
    ).toBe(true);
    expect(
      hasCounselReview("counselReview:\n  contentHash: ab12\nslabs: []"),
    ).toBe(true);
  });

  it("rejects null, a bare key, and a review without a hash", () => {
    expect(hasCounselReview("counselReview: null")).toBe(false);
    expect(hasCounselReview("counselReview: ~")).toBe(false);
    expect(hasCounselReview("counselReview:\nslabs: []")).toBe(false);
    expect(hasCounselReview("counselReview: { reviewer: c }")).toBe(false);
    expect(hasCounselReview("slabs: []")).toBe(false);
  });
});

describe("RELEASE_STATUS counsel gate", () => {
  it("has stamp-duty rule files for every stamping row", () => {
    for (const row of RELEASE_STATUS.filter((r) => r.stampingState)) {
      expect(ruleFiles(row.stampingState!).length).toBeGreaterThan(0);
    }
  });

  it("never shows stamping live where a rule file lacks counsel review", () => {
    const live = RELEASE_STATUS.filter(
      (r) => r.stampingState && r.state === "live",
    );
    for (const row of live) {
      for (const file of ruleFiles(row.stampingState!)) {
        expect(hasCounselReview(readFileSync(file, "utf8")), file).toBe(true);
      }
    }
  });
});
