import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import TemplatePicker, { isOffered } from "./TemplatePicker.vue";
import * as catalog from "../api/templateCatalog";
import * as jurisdictions from "../api/jurisdictions";
import type { TemplateSummary } from "../api/templateCatalog";

vi.mock("../api/templateCatalog", () => ({ listTemplates: vi.fn() }));
vi.mock("../api/jurisdictions", () => ({ fetchEligibleOrNone: vi.fn() }));
const mockedList = vi.mocked(catalog.listTemplates);
const mockedEligible = vi.mocked(jurisdictions.fetchEligibleOrNone);

function rows(): TemplateSummary[] {
  return [
    {
      id: "in-res",
      name: "Residential Rental Agreement (National)",
      description: "India-standard residential rental",
      type: "residential",
      state: "IN",
      language: "en",
      version: 1,
    },
    {
      id: "tg-res",
      name: "TG Residential Rental",
      description: "Telangana home rental",
      type: "residential",
      state: "TG",
      language: "en",
      version: 1,
    },
    {
      id: "tg-com",
      name: "TG Commercial Lease",
      description: "Shops and offices",
      type: "commercial",
      state: "TG",
      language: "en",
      version: 1,
    },
    {
      id: "ka-res",
      name: "KA Residential Rental",
      description: "Karnataka home rental",
      type: "residential",
      state: "KA",
      language: "en",
      version: 1,
    },
  ];
}

async function mountReady() {
  const wrapper = mount(TemplatePicker);
  await flushPromises();
  return wrapper;
}

describe("TemplatePicker", () => {
  beforeEach(() => {
    mockedList.mockReset();
    mockedList.mockResolvedValue(rows());
    mockedEligible.mockReset();
    mockedEligible.mockResolvedValue(["TG"]);
  });

  it("marks a jurisdiction the server cannot stamp as draft-and-download only", async () => {
    const w = mount(TemplatePicker);
    await flushPromises();

    expect(w.find('[data-testid="draft-only-ka-res"]').exists()).toBe(true);
    expect(w.find('[data-testid="draft-only-note-ka-res"]').text()).toContain(
      "Stamping and eSign are not yet available",
    );
    // The wording must not imply the template is unusable: it can still be drafted.
    expect(w.find('[data-testid="select-ka-res"]').text()).toBe(
      "Draft this template",
    );
  });

  it("leaves an eligible jurisdiction unmarked", async () => {
    const w = mount(TemplatePicker);
    await flushPromises();

    expect(w.find('[data-testid="draft-only-tg-res"]').exists()).toBe(false);
    expect(w.find('[data-testid="select-tg-res"]').text()).toBe(
      "Use this template",
    );
  });

  it("marks NOTHING when eligibility cannot be fetched, rather than marking everything", async () => {
    // Enforcement is server-side either way. Falsely telling an eligible customer they cannot be
    // stamped would turn a transient network error into a lost sale.
    mockedEligible.mockResolvedValue(null);

    const w = mount(TemplatePicker);
    await flushPromises();

    expect(w.find('[data-testid="draft-only-ka-res"]').exists()).toBe(false);
    expect(w.find('[data-testid="draft-only-tg-res"]').exists()).toBe(false);
  });

  it("joins on the state code regardless of case", async () => {
    mockedEligible.mockResolvedValue(["tg"]);

    const w = mount(TemplatePicker);
    await flushPromises();

    expect(w.find('[data-testid="draft-only-tg-res"]').exists()).toBe(false);
    expect(w.find('[data-testid="draft-only-ka-res"]').exists()).toBe(true);
  });

  it("lists the offered templates from the catalog", async () => {
    const wrapper = await mountReady();
    expect(mockedList).toHaveBeenCalledOnce();
    for (const id of ["tg-res", "ka-res"]) {
      expect(wrapper.find(`[data-testid="template-card-${id}"]`).exists()).toBe(
        true,
      );
    }
  });

  it("hides the national (IN) templates", async () => {
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="template-card-in-res"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="state-row-IN"]').exists()).toBe(false);
    const stateOptions = wrapper
      .findAll('[data-testid="filter-state"] option')
      .map((o) => o.text());
    expect(stateOptions).not.toContain("IN");
  });

  // Coupled to the backend tripwire ShippedCommercialRulesUnreviewedTest: commercial comes back only
  // when its duty rules carry a counsel review, and that test fails until this one is relaxed too.
  it("hides commercial templates", async () => {
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="template-card-tg-com"]').exists()).toBe(
      false,
    );
    const typeOptions = wrapper
      .findAll('[data-testid="filter-type"] option')
      .map((o) => o.text());
    expect(typeOptions).not.toContain("commercial");
    await wrapper.find('[data-testid="picker-search"]').setValue("commercial");
    await flushPromises();
    expect(wrapper.findAll('[data-testid^="template-card-"]')).toHaveLength(0);
  });

  it("hides the type filter when it would offer a single type", async () => {
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="filter-type"]').exists()).toBe(false);
  });

  it("groups templates into one row per state, sorted by state", async () => {
    const wrapper = await mountReady();
    const rowsRendered = wrapper.findAll('[data-testid^="state-row-"]');
    expect(rowsRendered.map((r) => r.attributes("data-testid"))).toEqual([
      "state-row-KA",
      "state-row-TG",
    ]);
    const tg = wrapper.find('[data-testid="state-row-TG"]');
    expect(tg.find('[data-testid="template-card-tg-res"]').exists()).toBe(true);
    expect(tg.find('[data-testid="template-card-ka-res"]').exists()).toBe(
      false,
    );
  });

  it("every offered template is selectable and none shows Coming soon (constraint lifted)", async () => {
    const wrapper = await mountReady();
    // generate-as-draft is dimension-aware now, so every listed published pair selects.
    for (const id of ["tg-res", "ka-res"]) {
      expect(
        wrapper.find(`[data-testid="select-${id}"]`).attributes("disabled"),
      ).toBeUndefined();
      expect(wrapper.find(`[data-testid="coming-soon-${id}"]`).exists()).toBe(
        false,
      );
    }
  });

  it("emits select with the chosen (state, type)", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="select-ka-res"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual([
      { state: "KA", type: "residential" },
    ]);
  });

  it("emits select for a non-default template (TG residential) too", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="select-tg-res"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual([
      { state: "TG", type: "residential" },
    ]);
  });

  it("filters by search query over name/description", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="picker-search"]').setValue("karnataka");
    await flushPromises();
    expect(wrapper.find('[data-testid="template-card-ka-res"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="template-card-tg-res"]').exists()).toBe(
      false,
    );
  });

  it("filters by state", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="filter-state"]').setValue("KA");
    await flushPromises();
    expect(wrapper.find('[data-testid="template-card-ka-res"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="template-card-tg-res"]').exists()).toBe(
      false,
    );
  });

  it("surfaces a load failure", async () => {
    mockedList.mockRejectedValue(new Error("Failed to load templates (500)."));
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="picker-error"]').exists()).toBe(true);
  });
});

describe("isOffered", () => {
  const t = (state: string, type: string): TemplateSummary => ({
    id: "x",
    name: "x",
    description: null,
    type,
    state,
    language: "en",
    version: 1,
  });

  it("offers a state residential template", () => {
    expect(isOffered(t("TG", "residential"))).toBe(true);
  });

  it("does not offer national, commercial or unknown-type templates", () => {
    expect(isOffered(t("IN", "residential"))).toBe(false);
    expect(isOffered(t("TG", "commercial"))).toBe(false);
    expect(isOffered(t("TG", "leave-and-license"))).toBe(false);
  });

  it("normalises casing and whitespace", () => {
    expect(isOffered(t(" ka ", " Residential "))).toBe(true);
    expect(isOffered(t(" in ", "residential"))).toBe(false);
    expect(isOffered(t("TG", " COMMERCIAL "))).toBe(false);
  });
});
