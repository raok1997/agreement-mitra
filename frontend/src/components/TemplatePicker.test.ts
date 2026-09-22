import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import TemplatePicker from "./TemplatePicker.vue";
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

    expect(w.find('[data-testid="draft-only-in-res"]').exists()).toBe(true);
    expect(w.find('[data-testid="draft-only-note-in-res"]').text()).toContain(
      "Stamping and eSign are not yet available",
    );
    // The wording must not imply the template is unusable: it can still be drafted.
    expect(w.find('[data-testid="select-in-res"]').text()).toBe(
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

    expect(w.find('[data-testid="draft-only-in-res"]').exists()).toBe(false);
    expect(w.find('[data-testid="draft-only-tg-res"]').exists()).toBe(false);
  });

  it("joins on the state code regardless of case", async () => {
    mockedEligible.mockResolvedValue(["tg"]);

    const w = mount(TemplatePicker);
    await flushPromises();

    expect(w.find('[data-testid="draft-only-tg-res"]').exists()).toBe(false);
    expect(w.find('[data-testid="draft-only-in-res"]').exists()).toBe(true);
  });

  it("lists published templates from the catalog", async () => {
    const wrapper = await mountReady();
    expect(mockedList).toHaveBeenCalledOnce();
    for (const id of ["in-res", "tg-res", "tg-com", "ka-res"]) {
      expect(wrapper.find(`[data-testid="template-card-${id}"]`).exists()).toBe(
        true,
      );
    }
  });

  it("every published template is selectable and none shows Coming soon (constraint lifted)", async () => {
    const wrapper = await mountReady();
    // generate-as-draft is dimension-aware now, so IN + TG (and any other published pair) all select.
    for (const id of ["in-res", "tg-res", "tg-com", "ka-res"]) {
      expect(
        wrapper.find(`[data-testid="select-${id}"]`).attributes("disabled"),
      ).toBeUndefined();
      expect(wrapper.find(`[data-testid="coming-soon-${id}"]`).exists()).toBe(
        false,
      );
    }
  });

  it("emits select with the chosen (state, type) for the default template", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="select-in-res"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual([
      { state: "IN", type: "residential" },
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
    await wrapper.find('[data-testid="picker-search"]').setValue("commercial");
    await flushPromises();
    expect(wrapper.find('[data-testid="template-card-tg-com"]').exists()).toBe(
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
