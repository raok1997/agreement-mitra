import { describe, expect, it } from "vitest";
import { readYamlDefault } from "../test-support/backendConfig";
import { GUARANTEES, PRICE, SUPPORT_HOURS } from "./promises";
import { clauseById } from "./legalDocument";
import { TERMS_OF_SERVICE } from "./termsOfService";

// The home page restates these figures; the terms and the backend fee config are where they are
// actually promised and charged. Any rewording of a clause fails here until the page follows.
function clause(id: string): string {
  return clauseById(TERMS_OF_SERVICE, id).body.join(" ");
}

describe("promises", () => {
  it("prices the page at the backend's default fee", () => {
    expect(PRICE.totalRupees * 100).toBe(
      Number(readYamlDefault("base-minor-units")),
    );
    expect(PRICE.includedStampRupees * 100).toBe(
      Number(readYamlDefault("included-stamp-value-minor-units")),
    );
  });

  it("states the fee the terms state (§7)", () => {
    expect(clause("our-fee")).toContain(
      `INR ${PRICE.totalRupees} where the stamp value on your agreement is INR ${PRICE.includedStampRupees} or less`,
    );
  });

  it("states the certificate refund the terms state (§8)", () => {
    expect(clause("stamp-duty")).toContain(
      `refund you INR ${GUARANTEES.certificateRefundRupees}`,
    );
  });

  it("states the signer-retry charge the terms state (§11)", () => {
    expect(clause("refunds")).toContain(
      `ask for INR ${GUARANTEES.signerRetryChargeRupees} before starting it again`,
    );
  });

  it("states the stamping target, delay credit and support hours the terms state (§14)", () => {
    const body = clause("availability-and-support");
    expect(body).toContain(
      `within ${GUARANTEES.stampTargetWorkingDaysText} working day of payment`,
    );
    expect(body).toContain(
      `more than ${GUARANTEES.delayGraceWorkingDaysText} working days late`,
    );
    expect(body).toContain(
      `INR ${GUARANTEES.delayCreditPerDayRupees} for each further working day, up to INR ${GUARANTEES.delayCreditCapRupees}`,
    );
    expect(body).toContain(SUPPORT_HOURS);
  });
});
