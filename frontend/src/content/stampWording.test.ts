import { describe, expect, it } from "vitest";
import { clauseById, type LegalDocument } from "./legalDocument";
import {
  PRIVACY_COLLECTED_CATEGORIES,
  PRIVACY_POLICY,
  PRIVACY_RECIPIENT_ROLES,
} from "./privacyPolicy";
import { TERMS_OF_SERVICE } from "./termsOfService";

// legal-policy-pages "Policy texts state the stamp channel per state". Karnataka stamps are SHCIL
// e-stamp certificates; Telangana stamps are non-judicial stamp paper from a licensed vendor. So no
// sentence may assume one medium: "certificate" belongs to the e-stamp alone, and nothing is
// bought "on a portal".

function texts(doc: LegalDocument): { id: string; text: string }[] {
  return doc.clauses.flatMap((c) =>
    [...c.body, c.gap ?? ""].map((text) => ({ id: c.id, text })),
  );
}

const ALL_TEXT = [
  ...texts(TERMS_OF_SERVICE),
  ...texts(PRIVACY_POLICY),
  ...PRIVACY_COLLECTED_CATEGORIES.map((text) => ({ id: "categories", text })),
  ...PRIVACY_RECIPIENT_ROLES.map((text) => ({ id: "roles", text })),
];

const clauseText = (doc: LegalDocument, id: string): string => {
  const c = clauseById(doc, id);
  return [...c.body, c.gap ?? ""].join(" ");
};

describe("stamp wording across the policy texts", () => {
  it("never says a stamp is bought on a portal or a government channel", () => {
    for (const { id, text } of ALL_TEXT) {
      expect(text, id).not.toMatch(/portal|government channel/i);
    }
  });

  it("uses certificate only for the e-stamp certificate", () => {
    for (const { id, text } of ALL_TEXT) {
      const stray = text
        .replace(/e-stamp certificate/gi, "")
        .match(/certificate/i);
      expect(stray, `${id}: ${text}`).toBeNull();
    }
  });

  it("names SHCIL only where the terms describe the stamp channel", () => {
    const withShcil = new Set(
      texts(TERMS_OF_SERVICE)
        .filter(({ text }) => /SHCIL/i.test(text))
        .map(({ id }) => id),
    );
    expect([...withShcil].sort()).toEqual(
      ["availability-and-support", "stamp-duty"].sort(),
    );
    for (const { text } of [
      ...texts(PRIVACY_POLICY),
      ...PRIVACY_COLLECTED_CATEGORIES.map((text) => ({ text })),
      ...PRIVACY_RECIPIENT_ROLES.map((text) => ({ text })),
    ]) {
      expect(text).not.toMatch(/SHCIL/i);
    }
  });

  it("ties each channel to its state in §8, and asks counsel about Telangana stamp paper", () => {
    const clause = clauseById(TERMS_OF_SERVICE, "stamp-duty");
    const body = clause.body.join(" ");
    expect(body).toMatch(
      /Karnataka agreement[^.]*e-stamp certificate[^.]*\(SHCIL\)/,
    );
    expect(body).toMatch(
      /Telangana agreement[^.]*non-judicial stamp paper bought from a licensed stamp vendor/,
    );
    expect(clause.gap).toContain(
      "whether a Telangana stamp paper, bought separately and attached to your agreement as a scan, validly stamps an agreement signed online",
    );
    expect(clause.gap).toContain(
      "whether the paper original has to be kept with it",
    );
  });

  it("names both stamp recipients and the on-request courier by role", () => {
    for (const role of [
      "Karnataka e-stamp issuer",
      "Telangana licensed stamp vendor",
      "courier, only if you ask us to send you a stamp paper original",
    ]) {
      expect(PRIVACY_RECIPIENT_ROLES).toContain(role);
    }
    const recipients = clauseText(PRIVACY_POLICY, "who-receives-it");
    expect(recipients).toContain("keeps its own record under its own rules");
    expect(recipients).toContain(
      "keeps them in its own register under those rules",
    );
  });

  it("accounts for the Telangana paper original in both texts", () => {
    const tos = clauseText(TERMS_OF_SERVICE, "stamp-duty");
    expect(tos).toContain(
      "we keep the paper original of the stamp for one year from the day we buy it, and then shred it",
    );
    expect(tos).toContain(
      "write to us within that year and we will arrange to have it sent to you",
    );
    expect(tos).not.toMatch(/free (of charge )?delivery|deliver[^.]* free/i);

    expect(clauseText(PRIVACY_POLICY, "what-we-collect")).toContain(
      "we also keep the paper original for one year, and then shred it",
    );
  });
});
