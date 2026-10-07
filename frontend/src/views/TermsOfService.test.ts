import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import TermsOfService from "./TermsOfService.vue";
import { clauseById } from "../content/legalDocument";
import { TERMS_OF_SERVICE } from "../content/termsOfService";
import { CONTACT_EMAIL, GRIEVANCE_EMAIL, PRICE } from "../content/promises";

// The page is published DELIBERATELY unfinished (docs/LEGAL-POSTURE.md item 2: a draft beats
// nothing). What makes that defensible rather than sloppy is that the unfinished parts announce
// themselves, so these tests are mostly about the holes being visible.
describe("TermsOfService", () => {
  it("never reads as a settled document", () => {
    const wrapper = mount(TermsOfService);
    const banner = wrapper.get('[data-testid="terms-draft-banner"]').text();
    expect(banner).toContain("Draft, pending legal review");
    expect(banner).toContain("pending review by Indian counsel");
  });

  it("renders every clause", () => {
    const text = mount(TermsOfService).text();
    for (const clause of TERMS_OF_SERVICE.clauses) {
      expect(text).toContain(clause.heading);
    }
  });

  it("shows a visible gap for every clause we have not written", () => {
    const wrapper = mount(TermsOfService);
    const gaps = wrapper.findAll('[data-testid="terms-gap"]');
    const unwritten = TERMS_OF_SERVICE.clauses.filter(
      (c) => c.status !== "drafted",
    );

    expect(unwritten.length).toBeGreaterThan(0);
    expect(gaps).toHaveLength(unwritten.length);
    for (const clause of unwritten) {
      expect(wrapper.text()).toContain(clause.gap);
    }
  });

  it("labels each gap with who owes it", () => {
    // Counsel gaps and undecided commercial terms are different problems with different owners.
    // Every commercial term is decided today, so only the counsel label renders.
    const text = mount(TermsOfService).text();
    expect(text).toContain("Gap - with our lawyers");
    expect(text).not.toContain("Gap - not yet decided");
  });

  it("states the things we committed to writing ourselves", () => {
    // The half docs/LEGAL-POSTURE.md item 2 says we write, as opposed to the half counsel completes.
    const text = mount(TermsOfService).text();
    expect(text).toContain("not a law firm");
    expect(text).toContain("generated from a template");
    expect(text).toContain(
      "Stamp duty is a tax levied by the state government",
    );
    expect(text).toContain("It is not our fee and we do not keep it");
    expect(text).toContain("licensed eSign Service Provider");
    expect(text).toContain("An unpaid draft is yours to abandon");
  });

  it("states every commercial term, leaving only legal questions open", () => {
    // The failure this guards against: a placeholder number reaching counsel as though intended.
    // The fee, the compensation for our own error, the turnaround and the refund once the stamp is
    // bought are all decided, so they are stated.
    const byId = (id: string) => clauseById(TERMS_OF_SERVICE, id);

    expect(byId("our-fee").status).toBe("drafted");
    expect(byId("availability-and-support").status).toBe("drafted");
    expect(byId("refunds").status).toBe("drafted");
    // Retention is decided; what remains on it is a legal question, not a
    // commercial one, so it carries a counsel gap rather than a product one.
    expect(byId("retention").status).toBe("counsel");
  });

  it("states the price as a rule on the chosen stamp value", () => {
    // payment-processing charges on the stamp value, not the legal duty, so the clause does too.
    // A clause saying only "INR 499" would be false for most agreements.
    const text = mount(TermsOfService).text();
    expect(text).toContain(
      `INR ${PRICE.totalRupees} where the stamp value on your agreement is INR ${PRICE.includedStampRupees} or less`,
    );
    expect(text).toContain(
      `the total is INR ${PRICE.totalRupees} plus the amount by which it exceeds INR ${PRICE.includedStampRupees}`,
    );
  });

  it("never pays back more than the customer actually paid", () => {
    // A fixed-sum remedy plus a discounted price is an arbitrage: without this, a customer who
    // paid INR 300 under a promotion could be refunded INR 400. Promotions do not exist in the
    // product yet -- the payment surface deliberately carries no discount field -- so this is the
    // rule waiting for them rather than a description of today.
    const text = mount(TermsOfService).text();
    expect(text).toContain(
      "we reduce that sum by the discount, to a minimum of nothing",
    );
    expect(text).toContain("never pay you back more than you actually paid us");
  });

  it("lets a customer go below the duty only after the under-stamping warning", () => {
    // stamp-selection: a below-duty stamp needs an audited acknowledgement. §7 paraphrases it.
    const text = mount(TermsOfService).text();
    expect(text).toContain(
      "You can go ahead with a stamp below the duty only after",
    );
    expect(text).not.toContain("still building");
    expect(text).not.toContain("corrected figure");
    // The promise that must survive whatever the pricing does: never a second bill.
    expect(text).toContain("take payment and then come back to you for more");
  });

  it("promises no compensation that scales with the customer's rent", () => {
    // Stamp duty is a pass-through we never earn and it scales with rent, so a remedy pegged to
    // it is a liability keyed to a number we do not control. Every remedy here is instead a fixed
    // rupee amount, at or below what we charge for the service.
    const text = mount(TermsOfService).text();
    expect(text).toContain("we refund you INR 400 for the trouble");
    expect(text).toContain(
      "INR 100 for each further working day, up to INR 400",
    );
    // No remedy may be reintroduced as a multiple or share of the duty.
    expect(text).not.toContain("equal to the stamp duty");
  });
});

// operating-entity-disclosure D6: the identifiers render after the clauses, never inside them.
describe("TermsOfService operator details", () => {
  const entity = {
    legalName: "KAVISAT TEK LABS LLP",
    llpin: null,
    registeredOffice: null,
    grievanceOfficer: null,
  };

  it("follows the last clause and names the LLP, its LLPIN state and the support email", () => {
    const wrapper = mount(TermsOfService, { props: { entity } });
    const details = wrapper.get('[data-testid="terms-operator-details"]');
    const text = details.text();

    expect(text).toContain("Operator details");
    expect(text).toContain("KAVISAT TEK LABS LLP");
    expect(text).toContain("being issued");
    expect(text).toContain("to be confirmed");
    expect(text).toContain(CONTACT_EMAIL);
    expect(text).toContain(`to be named, ${GRIEVANCE_EMAIL}`);

    const sections = wrapper.findAll("section").map((s) => s.element);
    const lastClause = sections
      .filter((el) =>
        el.getAttribute("data-testid")?.startsWith("terms-clause-"),
      )
      .at(-1)!;
    expect(
      lastClause.compareDocumentPosition(details.element) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("renders values as text, never as HTML", () => {
    for (const file of [
      "src/views/TermsOfService.vue",
      "src/components/OperatorDetails.vue",
    ]) {
      const source = readFileSync(resolve(process.cwd(), file), "utf8");
      expect(source, file).not.toContain("v-html");
    }
  });

  it("renders the unpaid-draft retention period in §10", () => {
    expect(mount(TermsOfService).text()).toContain(
      "Once an unpaid draft has gone 90 days without a change to its content, we delete it",
    );
  });
});
