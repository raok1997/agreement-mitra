import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import TermsOfService from "./TermsOfService.vue";
import { TERMS_CLAUSES } from "../content/termsOfService";

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
    for (const clause of TERMS_CLAUSES) {
      expect(text).toContain(clause.heading);
    }
  });

  it("shows a visible gap for every clause we have not written", () => {
    const wrapper = mount(TermsOfService);
    const gaps = wrapper.findAll('[data-testid="terms-gap"]');
    const unwritten = TERMS_CLAUSES.filter((c) => c.status !== "drafted");

    expect(unwritten.length).toBeGreaterThan(0);
    expect(gaps).toHaveLength(unwritten.length);
    for (const clause of unwritten) {
      expect(wrapper.text()).toContain(clause.gap);
    }
  });

  it("distinguishes a gap that is with counsel from one nobody has decided", () => {
    // The two are different problems with different owners, and conflating them would let a
    // commercial decision hide behind "the lawyers have it".
    const text = mount(TermsOfService).text();
    expect(text).toContain("Gap - with our lawyers");
    expect(text).toContain("Gap - not yet decided");
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

  it("leaves open only what nobody has decided, and marks it", () => {
    // The failure this guards against: a placeholder number reaching counsel as though intended.
    // The fee, the compensation for our own error and the turnaround are now decided, so they are
    // stated. What a customer gets back once we have already bought their certificate is not, and
    // it stays a visible gap rather than acquiring a plausible-looking number.
    const byHeading = (needle: string) =>
      TERMS_CLAUSES.find((c) => c.heading.includes(needle));

    expect(byHeading("Our fee")?.status).toBe("drafted");
    expect(byHeading("Availability and support")?.status).toBe("drafted");
    expect(byHeading("Refunds")?.status).toBe("product");
    // Retention is decided (three years); what remains on it is a legal question, not a
    // commercial one, so it carries a counsel gap rather than a product one.
    expect(byHeading("How long we keep things")?.status).toBe("counsel");
  });

  it("states the price as a rule, not as a single number that hides the duty", () => {
    // The total moves with stamp duty, and the whole pricing pillar is that the movement is
    // visible. A clause saying only "INR 499" would be false for most agreements.
    const text = mount(TermsOfService).text();
    expect(text).toContain("INR 499 where the stamp duty");
    expect(text).toContain("plus the amount by which the duty exceeds INR 100");
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

  it("does not claim a duty calculation the product does not yet do", () => {
    // The fee clause states the rule that will apply. The product charges a flat constant and
    // computes no duty, so the clause has to say so -- the same discipline as the landing page's
    // status board, and the reason there is no correctness claim to walk back.
    const text = mount(TermsOfService).text();
    expect(text).toContain(
      "still building the part that works the duty out automatically",
    );
    expect(text).toContain("we have been absorbing the difference");
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
