import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import App from "./App.vue";
import * as catalog from "./api/templateCatalog";
import * as templateForm from "./api/templateForm";
import * as documentPreview from "./api/documentPreview";
import * as client from "./api/client";
import type { TemplateSummary } from "./api/templateCatalog";
import type { FormSchema } from "./api/templateForm";

// Thin end-to-end through the App view-switch: pick a template -> fill required sections -> the live
// preview refreshes -> Save & continue creates + generates the draft. Only the network layer is mocked.
vi.mock("./api/templateCatalog", () => ({ listTemplates: vi.fn() }));
vi.mock("./api/templateForm", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/templateForm")>();
  return { ...actual, getTemplateForm: vi.fn() };
});
vi.mock("./api/documentPreview", () => ({
  fetchDocumentPreviewHtml: vi.fn(),
  fetchDocumentPreviewPdf: vi.fn(),
}));
vi.mock("./api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./api/client")>();
  return {
    ...actual,
    createAgreement: vi.fn(),
    generateAgreementDocument: vi.fn(),
  };
});

const mockedList = vi.mocked(catalog.listTemplates);
const mockedGetForm = vi.mocked(templateForm.getTemplateForm);
const mockedPreviewHtml = vi.mocked(documentPreview.fetchDocumentPreviewHtml);
const mockedCreate = vi.mocked(client.createAgreement);
const mockedGenerate = vi.mocked(client.generateAgreementDocument);

function catalogRows(): TemplateSummary[] {
  return [
    {
      id: "in-res",
      name: "Residential Rental Agreement (National)",
      description: "home rental",
      type: "residential",
      state: "IN",
      language: "en",
      version: 1,
    },
  ];
}

function schema(): FormSchema {
  return {
    dimensions: { state: "IN", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "h1",
    sections: [
      {
        title: "Parties",
        fields: [
          {
            key: "tenantName",
            label: "Tenant name",
            widget: "text",
            type: "text",
            required: true,
          },
          {
            key: "ownerName",
            label: "Owner name",
            widget: "text",
            type: "text",
            required: true,
          },
        ],
      },
      {
        title: "Property",
        fields: [
          {
            key: "propertyAddress",
            label: "Property address",
            widget: "textarea",
            type: "longtext",
            required: true,
          },
        ],
      },
    ],
  };
}

// Same as schema() but with one OPTIONAL section (Pets) presented in the Add-optional catalog.
function schemaWithOptional(): FormSchema {
  const base = schema();
  return {
    ...base,
    sections: [
      ...base.sections.map((s) => ({
        ...s,
        optional: false,
        renderKind: "keyvalue",
      })),
      {
        title: "Pets",
        optional: true,
        renderKind: "clauses",
        fields: [
          {
            key: "petNotes",
            label: "Pet notes",
            widget: "text",
            type: "text",
            required: false,
          },
        ],
      },
    ],
  };
}

describe("App end-to-end (pick -> fill -> preview -> save)", () => {
  beforeEach(() => {
    mockedList.mockReset().mockResolvedValue(catalogRows());
    mockedGetForm.mockReset().mockResolvedValue(schema());
    mockedPreviewHtml
      .mockReset()
      .mockResolvedValue("<html><body>preview</body></html>");
    mockedCreate.mockReset();
    mockedGenerate.mockReset();
    localStorage.clear();
  });

  it("picks a template, fills the form, and saves through the existing endpoints", async () => {
    mockedCreate.mockResolvedValue({
      id: "agr-9",
      propertyAddress: "12 MG Road",
      monthlyRent: 0,
      securityDeposit: 0,
      startDate: "",
      endDate: "",
      durationMonths: 0,
      createdAt: "2026-07-12T00:00:00Z",
      signers: [],
    });
    mockedGenerate.mockResolvedValue();

    const wrapper = mount(App);
    await flushPromises();

    // Step 1: the picker is the entry step; capture is not mounted yet.
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(false);

    // Step 2: choose the selectable (default) template -> capture mounts for (IN, residential).
    await wrapper.find('[data-testid="select-in-res"]').trigger("click");
    await flushPromises();
    expect(mockedGetForm).toHaveBeenCalledWith("IN", "residential");
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(true);

    // Step 3: fill the required sections.
    async function fill(sectionId: string, values: Record<string, string>) {
      await wrapper
        .find(`[data-testid="section-${sectionId}"]`)
        .trigger("click");
      for (const [k, v] of Object.entries(values)) {
        await wrapper.find(`[data-testid="field-${k}"]`).setValue(v);
      }
      await wrapper.find('[data-testid="modal-save"]').trigger("click");
      await flushPromises();
    }
    await fill("parties", { tenantName: "Tara Sen", ownerName: "Asha Rao" });
    await fill("property", { propertyAddress: "12 MG Road" });

    // Step 4: the live preview refreshed with the picked dimensions.
    expect(mockedPreviewHtml).toHaveBeenCalled();
    expect(mockedPreviewHtml.mock.calls.at(-1)?.[1]).toEqual({
      state: "IN",
      type: "residential",
    });

    // Step 5: Save & continue creates then generates the draft.
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledWith("agr-9");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("pick -> fill mandatory -> add one optional -> preview reflects it -> Save enables (M4)", async () => {
    mockedGetForm.mockReset().mockResolvedValue(schemaWithOptional());
    mockedCreate.mockResolvedValue({
      id: "agr-10",
      propertyAddress: "12 MG Road",
      monthlyRent: 0,
      securityDeposit: 0,
      startDate: "",
      endDate: "",
      durationMonths: 0,
      createdAt: "2026-07-12T00:00:00Z",
      signers: [],
    });
    mockedGenerate.mockResolvedValue();

    const wrapper = mount(App);
    await flushPromises();
    await wrapper.find('[data-testid="select-in-res"]').trigger("click");
    await flushPromises();

    const save = () => wrapper.find('[data-testid="save-continue"]');
    async function fill(sectionId: string, values: Record<string, string>) {
      await wrapper
        .find(`[data-testid="section-${sectionId}"]`)
        .trigger("click");
      for (const [k, v] of Object.entries(values)) {
        await wrapper.find(`[data-testid="field-${k}"]`).setValue(v);
      }
      await wrapper.find('[data-testid="modal-save"]').trigger("click");
      await flushPromises();
    }

    // Save stays disabled until the LAST mandatory section completes.
    expect(save().attributes("disabled")).toBeDefined();
    await fill("parties", { tenantName: "Tara Sen", ownerName: "Asha Rao" });
    expect(save().attributes("disabled")).toBeDefined();
    await fill("property", { propertyAddress: "12 MG Road" });
    expect(save().attributes("disabled")).toBeUndefined();

    // Add one optional section -> the subsequent preview POST carries its title in activeSections.
    mockedPreviewHtml.mockClear();
    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await new Promise((r) => setTimeout(r, 300)); // debounced preview refresh
    await flushPromises();
    expect(wrapper.find('[data-testid="active-optional-pets"]').exists()).toBe(
      true,
    );
    expect(mockedPreviewHtml.mock.calls.at(-1)?.[2]).toContain("Pets");

    // Save & continue still fires the existing create + generate-as-draft calls.
    await save().trigger("click");
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledWith("agr-10");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("Change template returns to the picker", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.find('[data-testid="select-in-res"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(true);

    await wrapper.find('[data-testid="change-template"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="picker-list"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-rail"]').exists()).toBe(false);
  });
});
