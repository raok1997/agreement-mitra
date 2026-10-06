import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import {
  MAX_YEAR,
  MIN_YEAR,
  formatIso,
  parseEntry,
  parseIso,
} from "./dateEntry";

const reason = (text: string) => {
  const r = parseEntry(text);
  return r.ok ? r.iso : r.reason;
};

describe("parseIso / formatIso", () => {
  it("round-trips a real date", () => {
    expect(parseIso("2026-01-08")).toEqual({ year: 2026, month: 1, day: 8 });
    expect(formatIso("2026-01-08")).toBe("08/01/2026");
  });

  it("returns null / empty for empty or malformed input without throwing", () => {
    for (const bad of ["", "abc", "2026-1-8", "08/01/2026", "2026-02-31"]) {
      expect(parseIso(bad)).toBeNull();
      expect(formatIso(bad)).toBe("");
    }
    expect(parseIso(undefined as unknown as string)).toBeNull();
  });

  it("applies no year bounds -- only calendar validity", () => {
    expect(parseIso("1800-01-01")).not.toBeNull();
  });

  it("formatIso never renders through the host locale", () => {
    const spies = [
      vi.spyOn(Date.prototype, "toLocaleDateString"),
      vi.spyOn(Date.prototype, "toLocaleString"),
      vi.spyOn(Intl, "DateTimeFormat"),
    ];
    try {
      expect(formatIso("2026-01-08")).toBe("08/01/2026");
      for (const spy of spies) expect(spy).not.toHaveBeenCalled();
    } finally {
      for (const spy of spies) spy.mockRestore();
    }
  });
});

describe("formatIso under a time zone behind UTC", () => {
  const original = process.env.TZ;
  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });
  afterAll(() => {
    process.env.TZ = original;
  });

  it("shows the stored day, not the previous one", () => {
    // Guard: the zone really took effect (EST is UTC-5 in January).
    expect(new Date(2026, 0, 8).getTimezoneOffset()).toBe(300);
    expect(formatIso("2026-01-08")).toBe("08/01/2026");
    expect(parseEntry("08/01/2026")).toEqual({ ok: true, iso: "2026-01-08" });
  });
});

describe("parseEntry", () => {
  it("accepts dd/mm/yyyy, d/m/yyyy, eight digits, and year-first ISO", () => {
    expect(reason("08/01/2026")).toBe("2026-01-08");
    expect(reason("8/1/2026")).toBe("2026-01-08");
    expect(reason("08012026")).toBe("2026-01-08");
    expect(reason(" 08/01/2026 ")).toBe("2026-01-08");
    expect(reason("2001-02-03")).toBe("2001-02-03");
  });

  it("rejects a day the month does not have", () => {
    expect(reason("31/02/2026")).toBe("impossible-date");
    expect(reason("31/04/2026")).toBe("impossible-date");
    expect(reason("2026-02-31")).toBe("impossible-date");
    expect(reason("00/01/2026")).toBe("impossible-date");
    expect(reason("08/13/2026")).toBe("impossible-date");
  });

  it("reports a two-digit or partial year as incomplete", () => {
    expect(reason("08/01/20")).toBe("incomplete");
    expect(reason("08/01/26")).toBe("incomplete");
    expect(reason("08/0")).toBe("incomplete");
    expect(reason("0801")).toBe("incomplete");
    expect(reason("")).toBe("incomplete");
  });

  it("refuses ambiguous or non-date text instead of guessing", () => {
    expect(reason("03-02-2001")).toBe("not-a-date");
    expect(reason("03.02.2001")).toBe("not-a-date");
    expect(reason("abc")).toBe("not-a-date");
    expect(reason("08/01/20261")).toBe("not-a-date");
    expect(reason("1/2/3/4")).toBe("not-a-date");
  });

  it("accepts a leap day only in a leap year", () => {
    expect(reason("29/02/2028")).toBe("2028-02-29");
    expect(reason("29/02/2027")).toBe("impossible-date");
    expect(reason("29/02/2000")).toBe("2000-02-29");
    expect(reason("29/02/2100")).toBe("impossible-date");
  });

  it("knows every month's length", () => {
    const lengths = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    lengths.forEach((days, i) => {
      const mm = String(i + 1).padStart(2, "0");
      expect(reason(`${days}/${mm}/2027`)).toBe(`2027-${mm}-${days}`);
      expect(reason(`${days + 1}/${mm}/2027`)).toBe("impossible-date");
    });
  });

  it("mirrors the server's PlausibleDates year bounds, checked before the round trip", () => {
    expect([MIN_YEAR, MAX_YEAR]).toEqual([1900, 2199]);
    expect(reason("31/12/1899")).toBe("out-of-range");
    expect(reason("01/01/2200")).toBe("out-of-range");
    expect(reason("08/01/0026")).toBe("out-of-range");
    expect(reason("29/02/2200")).toBe("out-of-range");
    expect(reason("01/01/1900")).toBe("1900-01-01");
    expect(reason("31/12/2199")).toBe("2199-12-31");
  });
});
