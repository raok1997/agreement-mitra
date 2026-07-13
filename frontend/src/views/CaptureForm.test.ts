import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import CaptureForm from "./CaptureForm.vue";
import * as client from "../api/client";
import * as documentPreview from "../api/documentPreview";
import * as templateForm from "../api/templateForm";
import type { FormSchema } from "../api/templateForm";

// Mock the network layer only.
vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return {
    ...actual,
    createAgreement: vi.fn(),
    generateAgreementDocument: vi.fn(),
  };
});
vi.mock("../api/documentPreview", () => ({
  fetchDocumentPreviewHtml: vi.fn(),
  fetchDocumentPreviewPdf: vi.fn(),
}));
vi.mock("../api/templateForm", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/templateForm")>();
  return { ...actual, getTemplateForm: vi.fn() };
});

const mockedCreate = vi.mocked(client.createAgreement);
const mockedGenerate = vi.mocked(client.generateAgreementDocument);
const mockedPreviewHtml = vi.mocked(documentPreview.fetchDocumentPreviewHtml);
const mockedPreviewPdf = vi.mocked(documentPreview.fetchDocumentPreviewPdf);
const mockedGetForm = vi.mocked(templateForm.getTemplateForm);

// A small reference-shaped schema exercising every widget: text, money, checkbox, select, textarea,
// date, number. Section ids are slugged titles: parties / financial-terms / property / term.
function sampleSchema(): FormSchema {
  return {
    dimensions: { state: "IN", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "hash-1",
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
        title: "Financial terms",
        fields: [
          {
            key: "monthlyRent",
            label: "Monthly rent",
            widget: "money",
            type: "money",
            required: true,
            validation: { min: 1 },
          },
          {
            key: "furnished",
            label: "Furnished",
            widget: "checkbox",
            type: "bool",
            required: false,
            default: false,
          },
          {
            key: "registrationResponsibility",
            label: "Registration",
            widget: "select",
            type: "enum",
            required: false,
            options: [
              { value: "owner", label: "Owner" },
              { value: "tenant", label: "Tenant" },
            ],
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
      {
        title: "Term",
        fields: [
          {
            key: "startDate",
            label: "Start date",
            widget: "date",
            type: "date",
            required: true,
          },
          {
            key: "endDate",
            label: "End date",
            widget: "date",
            type: "date",
            required: true,
          },
          {
            key: "durationMonths",
            label: "Duration",
            widget: "number",
            type: "int",
            required: true,
            validation: { min: 1, max: 60 },
          },
        ],
      },
    ],
  };
}

// A schema with an explicit MANDATORY vs OPTIONAL split (M3's FormSection.optional). Two mandatory
// sections (Parties, Property) and two optional ones (Pets, Parking) presented in the Add-optional
// catalog. Section ids are slugged titles.
function schemaWithOptional(): FormSchema {
  return {
    dimensions: { state: "IN", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "hash-opt",
    sections: [
      {
        title: "Parties",
        optional: false,
        renderKind: "parties",
        fields: [
          {
            key: "tenantName",
            label: "Tenant name",
            widget: "text",
            type: "text",
            required: true,
          },
        ],
      },
      {
        title: "Property",
        optional: false,
        renderKind: "keyvalue",
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
      {
        title: "Parking",
        optional: true,
        renderKind: "clauses",
        fields: [
          {
            key: "parkingSlot",
            label: "Parking slot",
            widget: "text",
            type: "text",
            required: false,
          },
        ],
      },
    ],
  };
}

function fakeAgreement(): client.AgreementView {
  return {
    id: "agr-1",
    propertyAddress: "12 MG Road",
    monthlyRent: 25000,
    securityDeposit: 0,
    startDate: "2026-01-01",
    endDate: "2026-12-01",
    durationMonths: 11,
    createdAt: "2026-07-10T00:00:00Z",
    signers: [],
  };
}

async function mountReady() {
  const wrapper = mount(CaptureForm);
  await flushPromises(); // resolve the on-mount schema fetch
  return wrapper;
}

async function fillSection(
  wrapper: ReturnType<typeof mount>,
  sectionId: string,
  values: Record<string, string>,
) {
  await wrapper.find(`[data-testid="section-${sectionId}"]`).trigger("click");
  for (const [key, value] of Object.entries(values)) {
    await wrapper.find(`[data-testid="field-${key}"]`).setValue(value);
  }
  await wrapper.find('[data-testid="modal-save"]').trigger("click");
  await flushPromises();
}

async function fillAllRequired(wrapper: ReturnType<typeof mount>) {
  await fillSection(wrapper, "parties", {
    tenantName: "Tara Sen",
    ownerName: "Asha Rao",
  });
  await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });
  await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
  await fillSection(wrapper, "term", {
    startDate: "2026-01-01",
    endDate: "2026-12-01",
    durationMonths: "11",
  });
}

describe("CaptureForm (schema-fed preview-centric shell)", () => {
  beforeEach(() => {
    mockedCreate.mockReset();
    mockedGenerate.mockReset();
    mockedPreviewHtml.mockReset();
    mockedPreviewPdf.mockReset();
    mockedGetForm.mockReset();
    mockedGetForm.mockResolvedValue(sampleSchema());
    mockedPreviewHtml.mockResolvedValue("<html><body>preview</body></html>");
    mockedPreviewPdf.mockResolvedValue(
      new Blob(["%PDF-"], { type: "application/pdf" }),
    );
    localStorage.clear();
    URL.createObjectURL = vi.fn(() => "blob:stub");
    URL.revokeObjectURL = vi.fn();
  });

  it("requests the form for the parity-safe default (IN, residential) dimensions", async () => {
    await mountReady();
    expect(mockedGetForm).toHaveBeenCalledWith("IN", "residential");
  });

  it("builds the section rail from the fetched schema and the required-section bar", async () => {
    const wrapper = await mountReady();
    for (const id of ["parties", "financial-terms", "property", "term"]) {
      expect(wrapper.find(`[data-testid="section-${id}"]`).exists()).toBe(true);
    }
    // 0 of 4 required sections at the start.
    expect(wrapper.find('[data-testid="completeness"]').text()).toContain("0");
    expect(wrapper.find('[data-testid="completeness"]').text()).toContain("4");
  });

  it("renders the right widget per field type", async () => {
    const wrapper = await mountReady();
    await wrapper
      .find('[data-testid="section-financial-terms"]')
      .trigger("click");
    expect(
      wrapper.find('[data-testid="field-monthlyRent"]').attributes("type"),
    ).toBe("number");
    await wrapper.find('[data-testid="section-property"]').trigger("click");
    expect(
      wrapper.find('[data-testid="field-propertyAddress"]').element.tagName,
    ).toBe("TEXTAREA");
  });

  it("PARITY GUARDRAIL: hides template fields the draft does not persist (furnished, registration)", async () => {
    // furnished + registrationResponsibility render from the effective template's DEFAULTS in the
    // signed draft, so exposing them for edit would let the live preview diverge from the draft. They
    // are hidden until M5 persists per-agreement attributes.
    const wrapper = await mountReady();
    await wrapper
      .find('[data-testid="section-financial-terms"]')
      .trigger("click");
    expect(wrapper.find('[data-testid="field-monthlyRent"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="field-furnished"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.find('[data-testid="field-registrationResponsibility"]').exists(),
    ).toBe(false);
  });

  it("enforces required and bounds client-side (modal error + incomplete section)", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    // Out-of-range duration surfaces a bound error and leaves the section incomplete.
    await wrapper.find('[data-testid="field-durationMonths"]').setValue("99");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-durationMonths"]').text(),
    ).toMatch(/at most 60/);
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="status-term"]').text()).toBe(
      "Needs input",
    );
  });

  it("saving a section updates the working set, refreshes the preview, and logs no PII", async () => {
    const logSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    const errSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const wrapper = await mountReady();
    mockedPreviewHtml.mockClear(); // ignore the initial on-mount refresh

    await fillSection(wrapper, "parties", {
      tenantName: "Tara Sen",
      ownerName: "Asha Rao",
    });
    await new Promise((r) => setTimeout(r, 300)); // debounced (250ms) preview refresh
    await flushPromises();

    expect(mockedPreviewHtml).toHaveBeenCalled();
    // The preview client is now called with (flat field-key data map, dimensions).
    const [sentData, sentDimensions] =
      mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(sentData?.tenantName).toBe("Tara Sen");
    expect(sentData?.ownerName).toBe("Asha Rao");
    // The parity-safe (IN, residential) dimensions -- the same the draft path renders -- are sent.
    expect(sentDimensions).toEqual({ state: "IN", type: "residential" });
    expect(wrapper.find('[data-testid="status-parties"]').text()).toBe("Ready");
    expect(wrapper.find('[data-testid="completeness"]').text()).toContain("1");

    const logged = [...logSpy.mock.calls, ...errSpy.mock.calls]
      .flat()
      .join(" ");
    expect(logged).not.toContain("Tara");
    logSpy.mockRestore();
    errSpy.mockRestore();
  });

  it("hard-blocks Save & continue until required sections are complete (disabled, no create call)", async () => {
    const wrapper = await mountReady();
    // The button is disabled (not merely warned) and a persistent affordance names the remaining count.
    const save = wrapper.find('[data-testid="save-continue"]');
    expect(save.attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="required-remaining"]').text()).toContain(
      "4",
    );

    await save.trigger("click");
    await flushPromises();
    expect(mockedCreate).not.toHaveBeenCalled();
  });

  it("Save & continue creates then generates the draft via the existing endpoints", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledWith("agr-1");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("Download PDF requests the application/pdf variant", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });

    await wrapper.find('[data-testid="download-pdf"]').trigger("click");
    await flushPromises();

    expect(mockedPreviewPdf).toHaveBeenCalledOnce();
  });

  it("keeps the working draft in localStorage and clears it after a successful save", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();

    await fillSection(wrapper, "parties", {
      tenantName: "Tara Sen",
      ownerName: "Asha Rao",
    });
    expect(localStorage.getItem("am.preview.draft.v1")).not.toBeNull();

    await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    await fillSection(wrapper, "term", {
      startDate: "2026-01-01",
      endDate: "2026-12-01",
      durationMonths: "11",
    });

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(localStorage.getItem("am.preview.draft.v1")).toBeNull();
  });

  it("Reset draft clears the client-held working set and storage", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    expect(localStorage.getItem("am.preview.draft.v1")).not.toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Ready",
    );

    await wrapper.find('[data-testid="reset"]').trigger("click");
    await flushPromises();

    expect(localStorage.getItem("am.preview.draft.v1")).toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Needs input",
    );
  });

  it("surfaces a schema-load failure without rendering a section rail", async () => {
    // The client keeps the probed dimensions out of the message; the shell shows only the terse error.
    mockedGetForm.mockRejectedValue(
      new Error("Failed to load the form (404)."),
    );
    const wrapper = await mountReady();
    expect(wrapper.find('[data-testid="schema-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-parties"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="schema-error"]').text()).not.toContain(
      "residential",
    );
  });
});

describe("CaptureForm: mandatory vs optional sections (M4)", () => {
  beforeEach(() => {
    mockedCreate.mockReset();
    mockedGenerate.mockReset();
    mockedPreviewHtml.mockReset();
    mockedPreviewPdf.mockReset();
    mockedGetForm.mockReset();
    mockedGetForm.mockResolvedValue(schemaWithOptional());
    mockedPreviewHtml.mockResolvedValue("<html><body>preview</body></html>");
    mockedPreviewPdf.mockResolvedValue(
      new Blob(["%PDF-"], { type: "application/pdf" }),
    );
    localStorage.clear();
    URL.createObjectURL = vi.fn(() => "blob:stub");
    URL.revokeObjectURL = vi.fn();
  });

  it("4.1: marks mandatory sections in the rail and optional ones in the Add-optional catalog", async () => {
    const wrapper = await mountReady();
    // Mandatory sections are listed with a Ready/Needs-input status; optional ones are NOT (no status).
    for (const id of ["parties", "property"]) {
      expect(wrapper.find(`[data-testid="section-${id}"]`).exists()).toBe(true);
      expect(wrapper.find(`[data-testid="status-${id}"]`).exists()).toBe(true);
    }
    // Optional sections sit in the Add-optional catalog, not in the mandatory list, and carry no status.
    for (const id of ["pets", "parking"]) {
      expect(wrapper.find(`[data-testid="catalog-${id}"]`).exists()).toBe(true);
      expect(wrapper.find(`[data-testid="add-optional-${id}"]`).exists()).toBe(
        true,
      );
      expect(wrapper.find(`[data-testid="status-${id}"]`).exists()).toBe(false);
    }
    // The completeness meter counts ONLY the two mandatory sections (optional never counts).
    expect(wrapper.find('[data-testid="completeness"]').text()).toContain("0");
    expect(wrapper.find('[data-testid="completeness"]').text()).toContain("2");
  });

  it("4.2: adding an optional section sends its title in activeSections and moves it to the active zone", async () => {
    const wrapper = await mountReady();
    mockedPreviewHtml.mockClear(); // ignore the on-mount refresh

    // Before adding: it is in the catalog, not the active zone, and no preview carries its title.
    expect(wrapper.find('[data-testid="active-optional-pets"]').exists()).toBe(
      false,
    );

    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await new Promise((r) => setTimeout(r, 300)); // debounced preview refresh
    await flushPromises();

    // (b) it moved into the active-optional zone and left the catalog.
    expect(wrapper.find('[data-testid="active-optional-pets"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="catalog-pets"]').exists()).toBe(false);
    // (a) the next preview POST carries "Pets" in activeSections, alongside the data map.
    const [sentData, , sentActive] = mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(sentActive).toContain("Pets");
    expect(sentData).toBeDefined();

    // Removing it drops the title from activeSections and returns it to the catalog.
    mockedPreviewHtml.mockClear();
    await wrapper.find('[data-testid="remove-optional-pets"]').trigger("click");
    await new Promise((r) => setTimeout(r, 300));
    await flushPromises();
    expect(wrapper.find('[data-testid="catalog-pets"]').exists()).toBe(true);
    const [, , afterRemove] = mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(afterRemove).not.toContain("Pets");
  });

  it("4.3: Save is disabled until every mandatory section is complete; optional never gates it", async () => {
    const wrapper = await mountReady();
    const save = () => wrapper.find('[data-testid="save-continue"]');

    // Two mandatory sections incomplete -> disabled, affordance names N = 2.
    expect(save().attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="required-remaining"]').text()).toContain(
      "2",
    );

    await fillSection(wrapper, "parties", { tenantName: "Tara Sen" });
    expect(save().attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="required-remaining"]').text()).toContain(
      "1",
    );

    // Adding an optional section and leaving it incomplete must NOT affect the block.
    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await flushPromises();
    expect(save().attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="required-remaining"]').text()).toContain(
      "1",
    );

    // Completing the LAST mandatory section enables Save and clears the affordance.
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    expect(save().attributes("disabled")).toBeUndefined();
    expect(wrapper.find('[data-testid="required-remaining"]').exists()).toBe(
      false,
    );
  });

  it("persists the added-optional set in the draft and reconciles it against the schema on load", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await flushPromises();

    const stored = JSON.parse(
      localStorage.getItem("am.preview.draft.v1") ?? "{}",
    );
    expect(stored.activeSections).toContain("Pets");
    wrapper.unmount();

    // Remount: the added optional set is restored from the draft (Pets is back in the active zone).
    const resumed = await mountReady();
    expect(resumed.find('[data-testid="active-optional-pets"]').exists()).toBe(
      true,
    );
  });
});
