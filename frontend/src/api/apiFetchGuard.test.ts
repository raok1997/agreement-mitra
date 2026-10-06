import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Every src/api call must go through apiFetch (http.ts), which adds the CSRF header and the
// reconcile hook. A bare fetch would silently fail every unsafe request once CSRF is enforced.

const API_DIR = dirname(fileURLToPath(import.meta.url));
const BARE_FETCH = /(?<![\w$.])fetch\(|window\.fetch\(|globalThis\.fetch\(/;

describe("src/api fetch guard", () => {
  it("no module other than http.ts calls fetch directly", () => {
    const offenders = readdirSync(API_DIR)
      .filter(
        (f) => f.endsWith(".ts") && !f.endsWith(".test.ts") && f !== "http.ts",
      )
      .filter((f) => BARE_FETCH.test(readFileSync(join(API_DIR, f), "utf8")));
    expect(offenders).toEqual([]);
  });

  it("the matcher catches the bare forms and passes apiFetch", () => {
    expect(BARE_FETCH.test("await fetch(url)")).toBe(true);
    expect(BARE_FETCH.test("window.fetch(url)")).toBe(true);
    expect(BARE_FETCH.test("globalThis.fetch(url)")).toBe(true);
    expect(BARE_FETCH.test("await apiFetch(url)")).toBe(false);
  });
});
