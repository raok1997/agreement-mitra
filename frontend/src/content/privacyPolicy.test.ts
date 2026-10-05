import { describe, expect, it } from "vitest";
import { clauseById } from "./legalDocument";
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

  it.each([
    "purposes-and-basis",
    "retention",
    "your-rights",
    "grievance",
    "transfers",
  ])("leaves %s to counsel", (id) => {
    expect(clauseById(PRIVACY_POLICY, id).status).toBe("counsel");
  });

  it("states no retention period of its own", () => {
    // Retention is counsel's; a period here would be a second copy of the terms' clause, or a guess.
    expect(text("retention")).not.toMatch(
      /\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)[\s-]*(hour|day|week|month|year)/i,
    );
    expect(text("retention")).toContain("terms of service");
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
