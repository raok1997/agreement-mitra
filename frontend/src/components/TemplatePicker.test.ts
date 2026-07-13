import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import TemplatePicker from "./TemplatePicker.vue";
import * as catalog from "../api/templateCatalog";
import type { TemplateSummary } from "../api/templateCatalog";

vi.mock("../api/templateCatalog", () => ({ listTemplates: vi.fn() }));
const mockedList = vi.mocked(catalog.listTemplates);

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
  });

  it("lists published templates from the catalog", async () => {
    const wrapper = await mountReady();
    expect(mockedList).toHaveBeenCalledOnce();
    for (const id of ["in-res", "tg-res", "tg-com", "ka-res"]) {
      expect(wrapper.find(`[data-testid="template-card-${id}"]`).exists()).toBe(true);
    }
  });

  it("every published template is selectable and none shows Coming soon (constraint lifted)", async () => {
    const wrapper = await mountReady();
    // generate-as-draft is dimension-aware now, so IN + TG (and any other published pair) all select.
    for (const id of ["in-res", "tg-res", "tg-com", "ka-res"]) {
      expect(wrapper.find(`[data-testid="select-${id}"]`).attributes("disabled")).toBeUndefined();
      expect(wrapper.find(`[data-testid="coming-soon-${id}"]`).exists()).toBe(false);
    }
  });

  it("emits select with the chosen (state, type) for the default template", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="select-in-res"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual([{ state: "IN", type: "residential" }]);
  });

  it("emits select for a non-default template (TG residential) too", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="select-tg-res"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual([{ state: "TG", type: "residential" }]);
  });

  it("filters by search query over name/description", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="picker-search"]').setValue("commercial");
    await flushPromises();
    expect(wrapper.find('[data-testid="template-card-tg-com"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="template-card-tg-res"]').exists()).toBe(false);
  });

  it("filters by state", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="filter-state"]').setValue("KA");
    await flushPromises();
    expect(wrapper.find('[data-testid="template-card-ka-res"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="template-card-tg-res"]').exists()).toBe(false);
  });

  it("surfaces a load failure", async () => {
    mockedList.mockRejectedValue(new Error("Failed to load templates (500)."));
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="picker-error"]').exists()).toBe(true);
  });
});
