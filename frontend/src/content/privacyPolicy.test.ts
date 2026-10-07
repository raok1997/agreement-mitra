import { describe, expect, it } from "vitest";
import { clauseById } from "./legalDocument";
import { GRIEVANCE_EMAIL } from "./promises";
import { TERMS_OF_SERVICE } from "./termsOfService";
import {
  PRIVACY_COLLECTED_CATEGORIES,
  PRIVACY_POLICY,
  PRIVACY_RECIPIENT_ROLES,
} from "./privacyPolicy";

function text(id: string): string {
  const clause = clauseById(PRIVACY_POLICY, id);
  return [...clause.body, clause.gap ?? ""].join(" ");
}

describe("privacy policy", () => {
  it("names the data fiduciary in a drafted clause", () => {
    const clause = clauseById(PRIVACY_POLICY, "who-we-are");
    expect(clause.status).toBe("drafted");
    expect(clause.body.join(" ")).toContain("KAVISAT TEK LABS LLP");
    expect(clause.body.join(" ")).toContain("data fiduciary");
  });

  it("drafts every clause, leaving nothing to counsel", () => {
    for (const clause of PRIVACY_POLICY.clauses) {
      expect(clause.status, clause.id).toBe("drafted");
    }
  });

  it("states the same agreement and draft periods the terms state", () => {
    // Each period is restated here, so a change on either side must fail until both agree.
    const retention = text("retention");
    const signedPeriod = "until three years after the tenancy ends";
    expect(retention).toContain(signedPeriod);
    expect(clauseById(TERMS_OF_SERVICE, "retention").body.join(" ")).toContain(
      signedPeriod,
    );
    expect(retention).toContain("90 days without a change");
    expect(clauseById(TERMS_OF_SERVICE, "drafts").body.join(" ")).toContain(
      "90 days without a change",
    );
  });

  it("sends rights requests and grievances to the grievance address", () => {
    expect(text("your-rights")).toContain(GRIEVANCE_EMAIL);
    expect(text("grievance")).toContain(GRIEVANCE_EMAIL);
    expect(text("grievance")).toContain("Data Protection Board of India");
  });

  it("covers a draft we delete in the deleted-drafts clause", () => {
    const clause = clauseById(PRIVACY_POLICY, "deleted-drafts");
    const body = text("deleted-drafts");
    expect(clause.heading).toBe("8. Deleted drafts");
    expect(body).toContain(
      "when we delete an unpaid draft that has gone 90 days without a change",
    );
    expect(body).toContain("the account it was saved to, if any");
    expect(body).not.toContain("the account that deleted it");
    expect(body).toContain("none of the parties' details");
    expect(body).not.toMatch(/deleted after/i);
    // No period for an agreement that has been finalised or gone to payment.
    expect(body).not.toMatch(/finalised|payment|three years/i);
  });

  it("discloses browser storage and the sign-in binding cookie", () => {
    const body = text("cookies-and-storage");
    expect(body).toContain("local storage");
    expect(body).toContain("sign-in binding");
    // The draft's age is checked only when it is read back, so it is never "deleted after" a period.
    expect(body).not.toMatch(/deleted after/i);
    expect(body).toContain("Signing out does not clear them");
  });

  it("names every recipient role, and no vendor", () => {
    const body = text("who-receives-it");
    for (const role of PRIVACY_RECIPIENT_ROLES) {
      expect(body, role).toContain(role);
    }
    for (const vendor of [
      "Zoop",
      "Leegality",
      "Razorpay",
      "ZeptoMail",
      "Cloudflare",
      "Google",
    ]) {
      expect(body, vendor).not.toMatch(new RegExp(vendor, "i"));
    }
  });

  it("names every collected category", () => {
    const body = text("what-we-collect");
    for (const category of PRIVACY_COLLECTED_CATEGORIES) {
      expect(body, category).toContain(category);
    }
  });

  it("says what we never hold, scoped to the Aadhaar OTP", () => {
    const body = text("what-we-do-not-hold");
    expect(body).toContain(
      "never ask for or store your Aadhaar number, virtual ID or Aadhaar one-time password",
    );
    expect(body).toContain("no card, UPI or bank details");
  });
});
