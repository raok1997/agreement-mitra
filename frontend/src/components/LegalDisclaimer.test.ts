import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LegalDisclaimer from "./LegalDisclaimer.vue";

// Before this component existed, the only "not legal advice" line on the service disclaimed the FAQ
// on the marketing page -- the marketing was disclaimed and the product was not
// (docs/LEGAL-POSTURE.md item 2). These assertions are about the three things that made it worth
// building: it says we are not a law firm, it hands the reader the terms, and it never prints.
describe("LegalDisclaimer", () => {
  it("says we are not a law firm and that this is not legal advice", () => {
    const text = mount(LegalDisclaimer).text();
    expect(text).toContain("not a law firm");
    expect(text).toContain("not legal advice");
  });

  it("points at the terms of service rather than ending the conversation there", () => {
    const link = mount(LegalDisclaimer).get(
      '[data-testid="legal-disclaimer-terms-link"]',
    );
    expect(link.attributes("href")).toBe("/terms");
  });

  it("is hidden in print in both variants", () => {
    // It is on-screen guidance about the service. The one screen carrying it that gets printed is
    // the payment confirmation, and what comes out of that is a receipt.
    for (const variant of ["inline", "bar"] as const) {
      const wrapper = mount(LegalDisclaimer, { props: { variant } });
      expect(
        wrapper.get('[data-testid="legal-disclaimer"]').classes(),
      ).toContain("print:hidden");
    }
  });

  it("spans the shell edge in the bar variant and sits in the flow otherwise", () => {
    const bar = mount(LegalDisclaimer, { props: { variant: "bar" } });
    expect(bar.get('[data-testid="legal-disclaimer"]').classes()).toContain(
      "border-t",
    );
    const inline = mount(LegalDisclaimer);
    expect(
      inline.get('[data-testid="legal-disclaimer"]').classes(),
    ).not.toContain("border-t");
  });
});
