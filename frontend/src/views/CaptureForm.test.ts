import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import CaptureForm from "./CaptureForm.vue";
import * as client from "../api/client";
import * as agreements from "../api/agreements";
import * as documentPreview from "../api/documentPreview";
import * as payments from "../api/payments";
import * as templateForm from "../api/templateForm";
import type { FormSchema } from "../api/templateForm";
import ContactConfirmation, {
  type PartyContact,
} from "./ContactConfirmation.vue";

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
// The pre-payment path. AgreementHttpError stays REAL: the component distinguishes a 409 by
// instanceof, so a stubbed error class would make the test agree with itself instead of with the
// code.
vi.mock("../api/agreements", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/agreements")>();
  return {
    ...actual,
    getAgreement: vi.fn(),
    updateAgreementContacts: vi.fn(),
    finaliseAgreement: vi.fn(),
    claimAgreement: vi.fn(),
  };
});
vi.mock("../api/payments", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/payments")>();
  return { ...actual, payForAgreement: vi.fn(), getPaymentProgress: vi.fn() };
});

const mockedCreate = vi.mocked(client.createAgreement);
const mockedGenerate = vi.mocked(client.generateAgreementDocument);
const mockedPreviewHtml = vi.mocked(documentPreview.fetchDocumentPreviewHtml);
const mockedPreviewPdf = vi.mocked(documentPreview.fetchDocumentPreviewPdf);
const mockedGetForm = vi.mocked(templateForm.getTemplateForm);
const mockedGetAgreement = vi.mocked(agreements.getAgreement);
const mockedUpdateContacts = vi.mocked(agreements.updateAgreementContacts);
const mockedFinalise = vi.mocked(agreements.finaliseAgreement);
const mockedPay = vi.mocked(payments.payForAgreement);

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
    trackingNumber: "AM-A5E4D7-010126",
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

/**
 * The agreement as the contact step reads it back: both parties already have an address, which is
 * the state a customer is in on a SECOND attempt after their first payment failed.
 */
function agreementWithContacts(): client.AgreementView {
  return {
    ...fakeAgreement(),
    signers: [
      {
        id: "signer-owner",
        name: "Asha Rao",
        firstName: "Asha",
        lastName: "Rao",
        fatherName: "Ravi Rao",
        currentAddress: "12 MG Road",
        email: "asha@example.com",
        mobile: null,
        role: "OWNER" as client.Role,
      },
      {
        id: "signer-tenant",
        name: "Tara Sen",
        firstName: "Tara",
        lastName: "Sen",
        fatherName: "Hari Sen",
        currentAddress: "3 C Street",
        email: "tara@example.com",
        mobile: null,
        role: "TENANT" as client.Role,
      },
    ],
  };
}

/** Drive the form to saved, then open the pre-payment contact step on it. */
async function reachContactStep(wrapper: ReturnType<typeof mount>) {
  await fillAllRequired(wrapper);
  await wrapper.find('[data-testid="save-continue"]').trigger("click");
  await flushPromises();
  await wrapper.find('[data-testid="finalise-and-pay"]').trigger("click");
  await flushPromises();
  return wrapper.findComponent(ContactConfirmation);
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
    mockedGetAgreement.mockReset();
    mockedUpdateContacts.mockReset();
    mockedFinalise.mockReset();
    mockedPay.mockReset();
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

  it("M5: un-hides fields the aggregate now persists (furnished, registration)", async () => {
    // agreement-capture-persistence (M5) persists the full capture state, so furnished +
    // registrationResponsibility now round-trip and render from the user's value on both faces --
    // the STOPGAP hide-list is retired for them (design D5). Only genuinely system-owned
    // template-default fields (e.g. stampDuty) stay hidden.
    const wrapper = await mountReady();
    await wrapper
      .find('[data-testid="section-financial-terms"]')
      .trigger("click");
    expect(wrapper.find('[data-testid="field-monthlyRent"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="field-furnished"]').exists()).toBe(true);
    expect(
      wrapper.find('[data-testid="field-registrationResponsibility"]').exists(),
    ).toBe(true);
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
    // Before save there is no reference: the preview/download passes no documentReference (4th arg),
    // so the server shows its PREVIEW marker.
    expect(mockedPreviewPdf.mock.calls.at(-1)?.[3]).toBeUndefined();
  });

  it("after save shows the tracking number and feeds it into the preview", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement()); // trackingNumber AM-A5E4D7-010126
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);

    mockedPreviewHtml.mockClear();
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    // The confirmation surfaces the reference to the user (UUID information at the client end).
    expect(wrapper.find('[data-testid="tracking-number"]').text()).toBe(
      "AM-A5E4D7-010126",
    );
    // The post-save preview refresh carries the tracking number as documentReference (4th arg), so
    // the on-screen preview body shows the real number instead of the marker.
    const carriedTheNumber = mockedPreviewHtml.mock.calls.some(
      (c) => c[3] === "AM-A5E4D7-010126",
    );
    expect(carriedTheNumber).toBe(true);
  });

  it("keeps the working draft in localStorage and clears it after a successful save", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();

    await fillSection(wrapper, "parties", {
      tenantName: "Tara Sen",
      ownerName: "Asha Rao",
    });
    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).not.toBeNull();

    await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    await fillSection(wrapper, "term", {
      startDate: "2026-01-01",
      endDate: "2026-12-01",
      durationMonths: "11",
    });

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
  });

  it("Reset draft clears the client-held working set and storage", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).not.toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Ready",
    );

    await wrapper.find('[data-testid="reset"]').trigger("click");
    await flushPromises();

    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
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
  // The pre-payment contact step on a RETRY. The first payment failed and the order is already
  // placed. The server now allows the save (contacts freeze at payment, not at finalise), but the
  // step still must not send a write it has no reason to send -- that pointless PATCH is what met
  // the old freeze and stranded a customer short of the pay button.
  it("retries payment without re-saving contacts that did not change", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    mockedGetAgreement.mockResolvedValue(agreementWithContacts());
    mockedFinalise.mockResolvedValue({
      agreementId: "agr-1",
      trackingReference: "AM-A5E4D7-010126",
      status: "PDF_GENERATED",
    });
    mockedPay.mockResolvedValue("FAILED");

    const wrapper = await mountReady();
    const step = await reachContactStep(wrapper);
    expect(step.exists()).toBe(true);

    // Confirm exactly what the server gave us -- the customer changed nothing, they just want to
    // pay again.
    step.vm.$emit(
      "confirm",
      agreementWithContacts().signers.map((signer) => ({
        id: signer.id,
        name: signer.name,
        role: signer.role,
        email: signer.email ?? "",
        mobile: signer.mobile ?? "",
      })) as PartyContact[],
    );
    await flushPromises();

    // No pointless PATCH: it would meet the freeze and strand the customer short of payment.
    expect(mockedUpdateContacts).not.toHaveBeenCalled();
    // ...and the retry actually happens. Finalise is idempotent, so this places no second order.
    expect(mockedFinalise).toHaveBeenCalledWith("agr-1");
    expect(mockedPay).toHaveBeenCalledOnce();
  });

  it("says a paid agreement's contacts are frozen instead of 'please try again'", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    mockedGetAgreement.mockResolvedValue(agreementWithContacts());
    // Contacts now freeze on PAYMENT, not on the order existing, so this is the only 409 the step
    // can meet -- and it is permanent, which is why the message must not invite a retry.
    mockedUpdateContacts.mockRejectedValue(
      new agreements.AgreementHttpError(
        409,
        "urn:agreementmitra:problem:contacts-frozen",
      ),
    );

    const wrapper = await mountReady();
    const step = await reachContactStep(wrapper);

    // This time the customer edits an address, which the frozen order genuinely cannot accept.
    step.vm.$emit("confirm", [
      {
        id: "signer-owner",
        name: "Asha Rao",
        role: "OWNER",
        email: "asha.new@example.com",
        mobile: "",
      },
      {
        id: "signer-tenant",
        name: "Tara Sen",
        role: "TENANT",
        email: "tara@example.com",
        mobile: "",
      },
    ] as PartyContact[]);
    await flushPromises();

    const message = step.props("error") ?? "";
    expect(message).toContain("already paid");
    // "Please try again" would be a lie: the freeze is permanent and retrying refuses forever.
    expect(message).not.toContain("try again");
    expect(mockedFinalise).not.toHaveBeenCalled();
  });

  it("still offers a retry for a failure that is not the contacts freeze", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    mockedGetAgreement.mockResolvedValue(agreementWithContacts());
    mockedUpdateContacts.mockRejectedValue(
      new agreements.AgreementHttpError(500),
    );

    const wrapper = await mountReady();
    const step = await reachContactStep(wrapper);

    step.vm.$emit("confirm", [
      {
        id: "signer-owner",
        name: "Asha Rao",
        role: "OWNER",
        email: "asha.new@example.com",
        mobile: "",
      },
    ] as PartyContact[]);
    await flushPromises();

    // A transient failure IS worth retrying, so the retryable message survives.
    expect(step.props("error") ?? "").toContain("try again");
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
      localStorage.getItem("am.preview.draft.v1.IN.residential") ?? "{}",
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

// agreement-capture-persistence (M5): Save sends the full capture state (captureData +
// activeSections); reopening an owned agreement for edit restores the added optional sections and
// dynamic values from the stored capture state. The anonymous drafting path is unchanged.
describe("CaptureForm: capture-state persistence (M5)", () => {
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

  it("sends captureData (the flat working set) + activeSections on Save", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();

    // Complete the two mandatory sections and add + fill an optional one.
    await fillSection(wrapper, "parties", { tenantName: "Tara Sen" });
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await flushPromises();
    await fillSection(wrapper, "pets", { petNotes: "One indoor cat" });

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(mockedCreate).toHaveBeenCalledOnce();
    const input = mockedCreate.mock.calls[0][0];
    // The full working set (fixed + dynamic) is sent as captureData ...
    expect(input.captureData).toMatchObject({
      tenantName: "Tara Sen",
      propertyAddress: "12 MG Road",
      petNotes: "One indoor cat",
    });
    // ... and the added optional section title as activeSections.
    expect(input.activeSections).toContain("Pets");
  });

  it("edit-reload re-activates a stored optional section and repopulates its dynamic value", async () => {
    const wrapper = mount(CaptureForm, {
      props: {
        agreementId: "agr-1",
        initialAgreement: {
          ...fakeAgreement(),
          captureData: {
            tenantName: "Tara Sen",
            propertyAddress: "12 MG Road",
            petNotes: "One indoor cat",
          },
          activeSections: ["Pets"],
        },
      },
    });
    await flushPromises();

    // The stored optional section is re-activated (moved out of the Add-optional catalog).
    expect(wrapper.find('[data-testid="active-optional-pets"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="catalog-pets"]').exists()).toBe(false);

    // Its dynamic value is repopulated: opening the section shows the stored value.
    await wrapper.find('[data-testid="section-pets"]').trigger("click");
    expect(
      (
        wrapper.find('[data-testid="field-petNotes"]')
          .element as HTMLInputElement
      ).value,
    ).toBe("One indoor cat");
  });
});
