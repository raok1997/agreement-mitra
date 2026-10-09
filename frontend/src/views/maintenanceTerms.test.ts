// Drift guard: maintenanceTerms.ts restates facts from the rental template. If base.yaml renames the
// section, a key, an option or the default, the capture rule and the key-terms line would silently go
// dead, so this pins the copy to the template text. Reads ../backend like backendConfig.ts does, with
// no YAML dependency: each match below fails closed on zero or several hits.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CHARGES_SECTION_TITLE,
  DEFAULT_MAINTENANCE_MODE,
  LEGACY_MAINTENANCE_KEY,
  MAINTENANCE_AMOUNT_KEY,
  MAINTENANCE_MODE_KEY,
  MAINTENANCE_MODES,
} from "./maintenanceTerms";

const SETS = "../backend/src/main/resources/documents/template/sets";

function readSet(path: string): string {
  return readFileSync(resolve(process.cwd(), SETS, path), "utf8");
}

function exactlyOne(
  text: string,
  pattern: RegExp,
  what: string,
): RegExpExecArray {
  const global = new RegExp(
    pattern.source,
    pattern.flags.includes("g") ? pattern.flags : pattern.flags + "g",
  );
  const matches = [...text.matchAll(global)];
  if (matches.length !== 1) {
    throw new Error(`expected exactly one ${what}, found ${matches.length}`);
  }
  return matches[0] as RegExpExecArray;
}

// The field declaration may wrap over several lines: `- { key: x, ... }` up to its closing brace.
function fieldDeclaration(yaml: string, key: string): string {
  return exactlyOne(
    yaml,
    new RegExp(`^\\s*- \\{ key: ${key},[^}]*\\}`, "m"),
    `"${key}" field`,
  )[0];
}

describe("maintenanceTerms matches the rental template", () => {
  const rental = readSet("rental/base.yaml");

  it("declares the Charges & Utilities section, listing both keys", () => {
    // Each section starts at a `- title:` line; the one whose block names the mode key is the one.
    const blocks = rental.split(/^\s*- title: /m).slice(1);
    const carrying = blocks.filter((b) =>
      new RegExp(`\\b${MAINTENANCE_MODE_KEY}\\b`).test(b.split(/\n\S/)[0]),
    );
    expect(carrying).toHaveLength(1);
    expect(carrying[0].startsWith(`"${CHARGES_SECTION_TITLE}"`)).toBe(true);
    expect(carrying[0]).toMatch(new RegExp(`\\b${MAINTENANCE_AMOUNT_KEY}\\b`));
  });

  it("declares the mode's options and default", () => {
    const mode = fieldDeclaration(rental, MAINTENANCE_MODE_KEY);
    const options = exactlyOne(mode, /options: \[([^\]]*)\]/, "options list")[1]
      .split(",")
      .map((o) => o.trim());
    expect(options).toEqual([...MAINTENANCE_MODES]);
    expect(exactlyOne(mode, /default: (\w+)/, "default")[1]).toBe(
      DEFAULT_MAINTENANCE_MODE,
    );
  });

  it("no longer declares the legacy key", () => {
    expect(rental).not.toMatch(
      new RegExp(`^\\s*- \\{ key: ${LEGACY_MAINTENANCE_KEY},`, "m"),
    );
  });

  it("declares the amount as a money field", () => {
    expect(fieldDeclaration(rental, MAINTENANCE_AMOUNT_KEY)).toMatch(
      /type: money/,
    );
  });

  it("is absent from the commercial template, which reuses the section title", () => {
    const commercial = readSet("commercial/base.yaml");
    expect(commercial).toContain(`title: "${CHARGES_SECTION_TITLE}"`);
    expect(commercial).not.toMatch(new RegExp(`\\b${MAINTENANCE_MODE_KEY}\\b`));
  });
});
