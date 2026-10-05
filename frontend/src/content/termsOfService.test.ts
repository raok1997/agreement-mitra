import { describe, expect, it } from "vitest";
import { clauseById } from "./legalDocument";
import { OPERATOR_LEGAL_NAME } from "./operatorFacts";
import { PRIVACY_POLICY } from "./privacyPolicy";
import { CONTACT_EMAIL } from "./promises";
import { TERMS_OF_SERVICE } from "./termsOfService";

// operating-entity-disclosure D6: §1 names the contracting party literally.
describe("terms of service, the operator", () => {
  it("names the operator in §1, verbatim", () => {
    const first = clauseById(TERMS_OF_SERVICE, "who-we-are");
    expect(first.heading).toMatch(/^1\. /);
    expect(first.body.join("\n")).toContain(OPERATOR_LEGAL_NAME);
  });
});

// legal-policy-pages: each piece of policy text has exactly one home.
describe("terms of service, single-sourced clauses", () => {
  it("makes §15 a pointer to the privacy policy that shares none of its text", () => {
    const clause = clauseById(TERMS_OF_SERVICE, "personal-data");
    expect(clause.heading).toBe("15. Your personal data");
    expect(clause.status).toBe("counsel");
    expect(clause.body).toHaveLength(1);
    expect(clause.body[0]).toContain("/privacy");

    const privacyParagraphs = new Set(
      PRIVACY_POLICY.clauses.flatMap((c) => c.body),
    );
    for (const paragraph of clause.body) {
      expect(privacyParagraphs.has(paragraph)).toBe(false);
    }
  });

  it("states the support email the rest of the site uses (§19)", () => {
    // Clause bodies are plain strings, so §19 holds the address literally; this pins it.
    expect(clauseById(TERMS_OF_SERVICE, "contact").body.join(" ")).toContain(
      CONTACT_EMAIL,
    );
  });
});
