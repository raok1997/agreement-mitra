import { describe, expect, it } from "vitest";
import { clauseById } from "./legalDocument";
import { OPERATOR_LEGAL_NAME } from "./operatorFacts";
import { PRIVACY_POLICY } from "./privacyPolicy";
import { CONTACT_EMAIL, GRIEVANCE_EMAIL } from "./promises";
import { RELEASE_STATE_LABEL } from "./releaseStatus";
import { TERMS_OF_SERVICE } from "./termsOfService";

// operating-entity-disclosure D6: §1 names the contracting party literally.
describe("terms of service, the operator", () => {
  it("names the operator in §1, verbatim", () => {
    const first = clauseById(TERMS_OF_SERVICE, "who-we-are");
    expect(first.heading).toMatch(/^1\. /);
    expect(first.body.join("\n")).toContain(OPERATOR_LEGAL_NAME);
  });
});

// jurisdiction-eligibility: §5 states the drafting scope and defers stampability to the server's
// decision, so the terms carry no live list that could drift from the board.
describe("terms of service, jurisdictions", () => {
  const body = (id: string) => clauseById(TERMS_OF_SERVICE, id).body.join(" ");

  it("states the drafting scope and keys the no-payment promise to the service", () => {
    const text = body("jurisdictions");
    for (const phrase of [
      "Telangana",
      "Karnataka",
      "residential",
      "We will not take payment for an agreement in a state we cannot stamp",
      "Draft and download only",
      "status board on our home page",
    ]) {
      expect(text, phrase).toContain(phrase);
    }
  });

  it.each(["what-the-service-does", "jurisdictions"])(
    "does not restate the live list in %s",
    (id) => {
      const text = body(id);
      expect(text).not.toContain("Today that is");
      for (const label of Object.values(RELEASE_STATE_LABEL)) {
        expect(text, label).not.toContain(label);
      }
    },
  );

  it("does not mention a template the picker hides", () => {
    expect(body("jurisdictions")).not.toContain("national template");
  });
});

// legal-policy-pages: each piece of policy text has exactly one home.
describe("terms of service, single-sourced clauses", () => {
  it("makes §15 a pointer to the privacy policy that shares none of its text", () => {
    const clause = clauseById(TERMS_OF_SERVICE, "personal-data");
    expect(clause.heading).toBe("15. Your personal data");
    expect(clause.status).toBe("drafted");
    expect(clause.body).toHaveLength(1);
    expect(clause.body[0]).toContain("/privacy");

    const privacyParagraphs = new Set(
      PRIVACY_POLICY.clauses.flatMap((c) => c.body),
    );
    for (const paragraph of clause.body) {
      expect(privacyParagraphs.has(paragraph)).toBe(false);
    }
  });

  it("states the grievance email the rest of the site uses (§17)", () => {
    expect(clauseById(TERMS_OF_SERVICE, "disputes").body.join(" ")).toContain(
      GRIEVANCE_EMAIL,
    );
  });

  it("states the support email the rest of the site uses (§19)", () => {
    // Clause bodies are plain strings, so §19 holds the address literally; this pins it.
    expect(clauseById(TERMS_OF_SERVICE, "contact").body.join(" ")).toContain(
      CONTACT_EMAIL,
    );
  });
});

// stale-draft-purge: §10 states the unpaid-draft period. The backend pins the same 90 days in
// DraftRetentionTest; a change on either side fails a test on that side.
describe("terms of service, unpaid-draft retention", () => {
  const drafts = () => clauseById(TERMS_OF_SERVICE, "drafts").body.join(" ");

  it("states the 90-day period and excludes finalised agreements", () => {
    const text = drafts();
    expect(text).toContain("90 days");
    expect(text).toContain("finalised");
    expect(text).toContain("whether or not it is saved to an account");
    expect(text).toContain("its link stops working");
  });

  it("does not describe the period as a discretion", () => {
    const text = drafts();
    expect(text).not.toContain("a long time");
    expect(text).not.toMatch(/may delete/i);
  });
});
