import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import PrivacyPolicy from "./PrivacyPolicy.vue";
import RefundPolicy from "./RefundPolicy.vue";
import ContactPage from "./ContactPage.vue";
import { PRIVACY_POLICY } from "../content/privacyPolicy";
import {
  CONTACT_EMAIL,
  CONTACT_PAGE_BANNER,
  SUPPORT_HOURS,
} from "../content/promises";
import {
  TERMS_OF_SERVICE,
  TERMS_REFUNDS_CLAUSE,
} from "../content/termsOfService";
import type { OperatingEntity } from "../content/operatingEntity";

const entity: OperatingEntity = {
  legalName: "KAVISAT TEK LABS LLP",
  llpin: null,
  registeredOffice: null,
};

const PAGES = [
  ["PrivacyPolicy", PrivacyPolicy, "privacy-policy"],
  ["RefundPolicy", RefundPolicy, "refund-policy"],
  ["ContactPage", ContactPage, "contact-page"],
] as const;

describe.each(PAGES)("%s", (name, component, rootTestId) => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("never reads as a settled document", () => {
    const banner = mount(component, { props: { entity } })
      .get('[data-testid="terms-draft-banner"]')
      .text();
    expect(banner).toContain("pending review by Indian counsel");
  });

  it("renders its root, a way back and the shared footer", async () => {
    const wrapper = mount(component, { props: { entity } });
    expect(wrapper.find(`[data-testid="${rootTestId}"]`).exists()).toBe(true);
    expect(wrapper.get("footer").text()).toContain(
      "AgreementMitra is a service of KAVISAT TEK LABS LLP.",
    );
    await wrapper.get('[data-testid="legal-back"]').trigger("click");
    expect(wrapper.emitted("back")).toHaveLength(1);
  });

  it("makes no request when it mounts", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    mount(component, { props: { entity } });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("renders values as text, never as HTML", () => {
    const source = readFileSync(
      resolve(process.cwd(), `src/views/${name}.vue`),
      "utf8",
    );
    expect(source).not.toContain("v-html");
  });
});

describe("PrivacyPolicy", () => {
  it("shows a gap box for every counsel clause, and the operator details", () => {
    const wrapper = mount(PrivacyPolicy, { props: { entity } });
    const unwritten = PRIVACY_POLICY.clauses.filter(
      (c) => c.status !== "drafted",
    );
    expect(unwritten.length).toBeGreaterThan(0);
    expect(wrapper.findAll('[data-testid="terms-gap"]')).toHaveLength(
      unwritten.length,
    );
    for (const clause of unwritten) {
      expect(wrapper.text()).toContain(clause.gap);
    }
    expect(
      wrapper.get('[data-testid="terms-operator-details"]').text(),
    ).toContain("KAVISAT TEK LABS LLP");
  });
});

describe("RefundPolicy", () => {
  it("shows exactly the terms' refunds clause, under the terms' banner", () => {
    const wrapper = mount(RefundPolicy, { props: { entity } });
    const sections = wrapper.findAll("section");
    expect(sections).toHaveLength(1);
    const clause = sections[0];

    expect(clause.get("h2").text()).toBe(TERMS_REFUNDS_CLAUSE.heading);
    expect(clause.get('[data-testid="terms-gap"]').text()).toContain(
      TERMS_REFUNDS_CLAUSE.gap,
    );
    const paragraphs = clause
      .findAll("p")
      .filter((p) => p.attributes("data-testid") !== "terms-gap")
      .map((p) => p.text());
    expect(paragraphs).toEqual(TERMS_REFUNDS_CLAUSE.body);

    expect(wrapper.get('[data-testid="terms-draft-banner"]').text()).toContain(
      TERMS_OF_SERVICE.banner,
    );
    expect(
      wrapper.get('[data-testid="refund-intro"] a').attributes("href"),
    ).toBe("/terms");
  });
});

describe("draft banner lead-in", () => {
  it("marks the policy texts as drafts", () => {
    for (const page of [PrivacyPolicy, RefundPolicy]) {
      expect(
        mount(page, { props: { entity } })
          .get('[data-testid="terms-draft-banner"]')
          .text(),
      ).toContain("Draft, pending legal review.");
    }
  });

  it("does not call the contact page itself a draft (its details are current)", () => {
    const banner = mount(ContactPage, { props: { entity } })
      .get('[data-testid="terms-draft-banner"]')
      .text();
    expect(banner).not.toContain("Draft, pending legal review.");
    expect(banner).toContain("The contact details below are current.");
  });
});

describe("ContactPage", () => {
  it("shows the support mailbox, hours and the operator", () => {
    const wrapper = mount(ContactPage, { props: { entity } });
    expect(wrapper.get('[data-testid="terms-draft-banner"]').text()).toContain(
      CONTACT_PAGE_BANNER,
    );
    expect(
      wrapper.get('[data-testid="contact-email"]').attributes("href"),
    ).toBe(`mailto:${CONTACT_EMAIL}`);
    expect(wrapper.get('[data-testid="contact-hours"]').text()).toBe(
      SUPPORT_HOURS,
    );
    const details = wrapper
      .get('[data-testid="terms-operator-details"]')
      .text();
    expect(details).toContain("KAVISAT TEK LABS LLP");
    expect(details).toContain("being issued");
  });
});
