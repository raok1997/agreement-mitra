import { describe, expect, it } from "vitest";
import { readYamlDefault } from "../test-support/backendConfig";
import { GUARANTEES, PRICE, SUPPORT_HOURS } from "./promises";
import { TERMS_CLAUSES } from "./termsOfService";

// The home page restates these figures; the terms and the backend fee config are where they are
// actually promised and charged. Any rewording of a clause fails here until the page follows.
function clause(n: number): string {
  const found = TERMS_CLAUSES.find((c) => c.heading.startsWith(`${n}. `));
  expect(found, `ToS clause ${n}`).toBeDefined();
  return found!.body.join(" ");
}

describe("promises", () => {
  it("prices the page at the backend's default fee", () => {
    expect(PRICE.totalRupees * 100).toBe(
      Number(readYamlDefault("base-minor-units")),
    );
    expect(PRICE.includedDutyRupees * 100).toBe(
      Number(readYamlDefault("included-stamp-value-minor-units")),
    );
  });

  it("states the fee the terms state (§7)", () => {
    expect(clause(7)).toContain(
      `INR ${PRICE.totalRupees} where the stamp duty on your agreement is INR ${PRICE.includedDutyRupees} or less`,
    );
  });

  it("states the certificate refund the terms state (§8)", () => {
    expect(clause(8)).toContain(
      `refund you INR ${GUARANTEES.certificateRefundRupees}`,
    );
  });

  it("states the signer-retry charge the terms state (§11)", () => {
    expect(clause(11)).toContain(
      `ask for INR ${GUARANTEES.signerRetryChargeRupees} before starting it again`,
    );
  });

  it("states the stamping target, delay credit and support hours the terms state (§14)", () => {
    const body = clause(14);
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
