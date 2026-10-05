import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import LegalDisclaimer from "./LegalDisclaimer.vue";
import { readYamlDefault } from "../test-support/backendConfig";

const squash = (s: string): string => s.replace(/\s+/g, " ").trim();

// The spec's forbidden-claims list (landing-page "no unearned legal claims").
const FORBIDDEN = [
  "100%",
  "legally guaranteed",
  "guaranteed valid",
  "legally valid agreement",
  "court-approved",
  "government-approved",
  "reviewed by counsel",
  "reviewed by a lawyer",
  "lawyer-reviewed",
];

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

  it("leads with what we stand behind, then the customer's part, then the short disclaimer", () => {
    for (const variant of ["inline", "bar"] as const) {
      const text = squash(
        mount(LegalDisclaimer, { props: { variant } }).text(),
      );
      const order = [
        "The wording of this agreement is ours",
        "The facts you enter and the choices you make are yours",
        "not a law firm",
        "no lawyer reviews",
        "not legal advice",
      ].map((phrase) => text.indexOf(phrase));
      expect(order.every((i) => i >= 0)).toBe(true);
      expect([...order].sort((a, b) => a - b)).toEqual(order);
    }
  });

  it("makes no unearned legal claim and renders no raw HTML", () => {
    const text = mount(LegalDisclaimer).text().toLowerCase();
    for (const phrase of FORBIDDEN) expect(text).not.toContain(phrase);
    const source = readFileSync(
      resolve(process.cwd(), "src/components/LegalDisclaimer.vue"),
      "utf8",
    );
    expect(source).not.toContain("v-html");
  });

  it("matches the on-preview screen notice in application.yml word for word", () => {
    // documents.footer.screen-notice is the same disclaimer under the document preview, as plain
    // text: the link renders as its address. One wording across the service (LEGAL-POSTURE item 2).
    const text = squash(
      mount(LegalDisclaimer)
        .text()
        .replace(
          "terms of service",
          "terms of service at agreementmitra.com/terms",
        ),
    );
    expect(squash(readYamlDefault("screen-notice"))).toBe(text);
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
