import { describe, it, expect } from "vitest";
import type { AgreementSummary } from "../api/agreements";
import { editedAgo, formatRupees, matchesQuery } from "./agreementListFormat";

// Local-time constructors throughout: the bands are defined by the browser's calendar.
const NOW = new Date(2026, 9, 4, 14, 0, 0);
const at = (...parts: [number, number, number, number, number]) =>
  new Date(...parts).toISOString();

describe("editedAgo", () => {
  it("reads a future instant as just now", () => {
    expect(editedAgo(at(2026, 9, 4, 14, 5), NOW)).toBe("just now");
  });

  it("reads under a minute as just now", () => {
    expect(editedAgo(new Date(NOW.getTime() - 30_000).toISOString(), NOW)).toBe(
      "just now",
    );
  });

  it("counts minutes under an hour", () => {
    expect(editedAgo(at(2026, 9, 4, 13, 15), NOW)).toBe("45 min ago");
  });

  it("counts hours earlier the same day", () => {
    expect(editedAgo(at(2026, 9, 4, 12, 0), NOW)).toBe("2h ago");
  });

  it("says yesterday for the previous calendar day", () => {
    expect(editedAgo(at(2026, 9, 3, 23, 30), NOW)).toBe("yesterday");
  });

  it("counts calendar days, not 24-hour spans", () => {
    // 23:00 two days back is under 48 hours ago but is not "yesterday".
    expect(editedAgo(at(2026, 9, 2, 23, 0), NOW)).toBe("2 days ago");
  });

  it("counts days up to 29", () => {
    expect(editedAgo(at(2026, 8, 5, 10, 0), NOW)).toBe("29 days ago");
  });

  it("falls back to dd/mm/yyyy from 30 days", () => {
    expect(editedAgo(at(2026, 8, 4, 10, 0), NOW)).toBe("on 04/09/2026");
  });
});

describe("formatRupees", () => {
  it("groups digits the Indian way with no paise", () => {
    expect(formatRupees(125000)).toBe("₹1,25,000");
    expect(formatRupees(32000)).toBe("₹32,000");
  });
});

function summary(over: Partial<AgreementSummary>): AgreementSummary {
  return {
    id: "a",
    trackingNumber: "AM7F3C21KX",
    propertyAddress: "Flat 402, Road No. 12, Banjara Hills, Hyderabad",
    monthlyRent: 32000,
    startDate: "2026-11-01",
    endDate: "2027-09-30",
    durationMonths: 11,
    createdAt: "2026-10-01T00:00:00Z",
    lastEditedAt: "2026-10-01T00:00:00Z",
    ownerNames: ["Ramesh Kumar Reddy"],
    tenantNames: ["Rohan Deshpande", "Mohammed Faizan"],
    status: "DRAFT",
    editable: true,
    deletable: false,
    ...over,
  };
}

describe("matchesQuery", () => {
  it("matches a party who is not listed first", () => {
    expect(matchesQuery(summary({}), "faizan")).toBe(true);
  });

  it("matches the reference", () => {
    expect(matchesQuery(summary({}), "7f3c")).toBe(true);
  });

  it("matches a word from the address", () => {
    expect(matchesQuery(summary({}), "banjara")).toBe(true);
  });

  it("requires every term to match", () => {
    expect(matchesQuery(summary({}), "ramesh banjara")).toBe(true);
    expect(
      matchesQuery(
        summary({ propertyAddress: "Plot 45, Madhapur, Hyderabad" }),
        "ramesh banjara",
      ),
    ).toBe(false);
  });

  it("matches everything on an empty or blank query", () => {
    expect(matchesQuery(summary({}), "")).toBe(true);
    expect(matchesQuery(summary({}), "   ")).toBe(true);
  });

  it("ignores case", () => {
    expect(matchesQuery(summary({}), "RAMESH")).toBe(true);
  });

  it("rejects a term that appears nowhere", () => {
    expect(matchesQuery(summary({}), "koramangala")).toBe(false);
  });
});
