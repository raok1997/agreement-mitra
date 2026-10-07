import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import SiteFooter from "./SiteFooter.vue";
import { CONTACT_EMAIL } from "../content/promises";
import type { OperatingEntity } from "../content/operatingEntity";

const NOT_YET_ISSUED: OperatingEntity = {
  legalName: "KAVISAT TEK LABS LLP",
  llpin: null,
  registeredOffice: null,
  grievanceOfficer: null,
};

const ISSUED: OperatingEntity = {
  legalName: "KAVISAT TEK LABS LLP",
  llpin: "ACA-1234",
  registeredOffice: "Plot 12, Road No. 3, Hyderabad - 500034",
  grievanceOfficer: null,
};

describe("SiteFooter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("names the operator", () => {
    const wrapper = mount(SiteFooter, { props: { entity: NOT_YET_ISSUED } });
    expect(wrapper.get('[data-testid="footer-operator"]').text()).toBe(
      "AgreementMitra is a service of KAVISAT TEK LABS LLP.",
    );
  });

  it("says the LLPIN is being issued rather than printing a dummy", () => {
    const wrapper = mount(SiteFooter, { props: { entity: NOT_YET_ISSUED } });
    expect(wrapper.get('[data-testid="footer-llpin"]').text()).toBe(
      "LLPIN: being issued",
    );
  });

  it("shows a configured LLPIN", () => {
    const wrapper = mount(SiteFooter, { props: { entity: ISSUED } });
    expect(wrapper.get('[data-testid="footer-llpin"]').text()).toBe(
      "LLPIN: ACA-1234",
    );
  });

  it("omits the registered office until it is configured", () => {
    expect(
      mount(SiteFooter, { props: { entity: NOT_YET_ISSUED } })
        .find('[data-testid="footer-office"]')
        .exists(),
    ).toBe(false);
    expect(
      mount(SiteFooter, { props: { entity: ISSUED } })
        .get('[data-testid="footer-office"]')
        .text(),
    ).toContain(ISSUED.registeredOffice);
  });

  it("links to every policy page and to the support mailbox", () => {
    const wrapper = mount(SiteFooter, { props: { entity: NOT_YET_ISSUED } });
    for (const path of ["/terms", "/privacy", "/refunds", "/contact"]) {
      expect(wrapper.find(`footer a[href="${path}"]`).exists(), path).toBe(
        true,
      );
    }
    expect(wrapper.get('footer a[href^="mailto:"]').attributes("href")).toBe(
      `mailto:${CONTACT_EMAIL}`,
    );
  });

  it("makes no request when it mounts", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    mount(SiteFooter, { props: { entity: ISSUED } });
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("renders values as text, never as HTML", () => {
    const source = readFileSync(
      resolve(process.cwd(), "src/components/SiteFooter.vue"),
      "utf8",
    );
    expect(source).not.toContain("v-html");
  });
});
