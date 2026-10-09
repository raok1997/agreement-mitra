import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { ServiceBusyError } from "../api/http";
import CaptureForm from "./CaptureForm.vue";
import * as client from "../api/client";
import * as agreements from "../api/agreements";
import * as documentPreview from "../api/documentPreview";
import * as payments from "../api/payments";
import * as templateForm from "../api/templateForm";
import * as jurisdictions from "../api/jurisdictions";
import * as authStore from "../api/authStore";
import type { Ref } from "vue";
import type { FormSchema } from "../api/templateForm";
import StampQuoteStep from "./StampQuoteStep.vue";
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
// Signed out and already ready: the create path awaits whenReady() before deciding on auto-claim,
// so a never-resolving promise here would hang every save test.
vi.mock("../api/authStore", async () => {
  const { ref } = await import("vue");
  return {
    isSignedIn: ref(false),
    whenReady: vi.fn(() => Promise.resolve()),
  };
});
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
vi.mock("../api/jurisdictions", () => ({ fetchEligibleOrNone: vi.fn() }));
vi.mock("../api/stampQuote", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/stampQuote")>();
  return { ...actual, getStampQuote: vi.fn(() => new Promise(() => {})) };
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
// Defaulted to ["TG"] in every beforeEach so a test that does not care about the jurisdiction
// banner still runs with a KNOWN eligibility list. Without that the on-mount fetch stays unresolved
// and the banner is absent for the wrong reason -- "we never found out" rather than "eligible" --
// which would let a regression that mislabels an eligible jurisdiction pass unnoticed.
const mockedEligible = vi.mocked(jurisdictions.fetchEligibleOrNone);

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
          // DERIVED: the server computes this from the dates, so it is projected read-only, not
          // required, and with no default -- the shape FormProjector now emits for `source: derived`.
          {
            key: "durationMonths",
            label: "Duration",
            widget: "number",
            type: "int",
            required: false,
            readOnly: true,
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
  // No durationMonths: it is DERIVED from these dates (11 whole months) and rendered as a display,
  // not an input -- there is nothing to set, and setting it would be setting a value the server
  // discards anyway.
  await fillSection(wrapper, "term", {
    startDate: "01/01/2026",
    endDate: "01/12/2026",
  });
}

describe("CaptureForm (schema-fed preview-centric shell)", () => {
  beforeEach(() => {
    mockedCreate.mockReset();
    mockedGenerate.mockReset();
    mockedPreviewHtml.mockReset();
    mockedPreviewPdf.mockReset();
    mockedGetForm.mockReset();
    mockedEligible.mockReset();
    mockedEligible.mockResolvedValue(["TG"]);
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
    await wrapper
      .find('[data-testid="section-financial-terms"]')
      .trigger("click");
    // Out-of-range rent surfaces a bound error and leaves the section incomplete. (The duration
    // used to carry this assertion; it is now a derived read-only display with no bounds of its
    // own, so the check moved to a field the customer actually types into.)
    await wrapper.find('[data-testid="field-monthlyRent"]').setValue("0");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-monthlyRent"]').text(),
    ).toMatch(/at least 1/);
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="status-financial-terms"]').text()).toBe(
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
    await new Promise((r) => setTimeout(r, 650)); // debounced (~600ms) preview refresh
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

  it("debounces the live preview at ~600 ms, not per keystroke", async () => {
    const wrapper = await mountReady();
    mockedPreviewHtml.mockClear();

    await fillSection(wrapper, "parties", { tenantName: "Tara Sen" });
    await new Promise((r) => setTimeout(r, 400));
    await flushPromises();
    expect(mockedPreviewHtml).not.toHaveBeenCalled(); // the old 250 ms debounce would have fired

    await new Promise((r) => setTimeout(r, 300));
    await flushPromises();
    expect(mockedPreviewHtml).toHaveBeenCalledOnce();
  });

  it("shows a retry message, not a raw status, when the preview is refused for load", async () => {
    const wrapper = await mountReady();
    mockedPreviewHtml.mockRejectedValue(new ServiceBusyError(7));

    await fillSection(wrapper, "parties", { tenantName: "Tara Sen" });
    await new Promise((r) => setTimeout(r, 650));
    await flushPromises();

    expect(wrapper.text()).toContain("try again in 7 seconds");
    expect(wrapper.text()).not.toMatch(/\b429\b|\b503\b/);
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

  it("keeps Save & continue disabled until a required enum with no default is chosen", async () => {
    // The sub-letting shape: required, no default, so the select opens on "Select..." and the Term
    // section stays incomplete until an option is picked (subletting-choice).
    const schema = sampleSchema();
    schema.sections
      .find((s) => s.title === "Term")!
      .fields.push({
        key: "subletting",
        label: "Sub-letting",
        widget: "select",
        type: "enum",
        required: true,
        options: [
          { value: "with_owner_consent", label: "With Owner Consent" },
          { value: "not_allowed", label: "Not Allowed" },
          { value: "allowed", label: "Allowed" },
        ],
      });
    mockedGetForm.mockResolvedValue(schema);
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    const save = () => wrapper.find('[data-testid="save-continue"]');
    expect(save().attributes("disabled")).toBeDefined();
    expect(wrapper.find('[data-testid="required-remaining"]').text()).toContain(
      "1",
    );

    await wrapper.find('[data-testid="section-term"]').trigger("click");
    const select = wrapper.find('[data-testid="field-subletting"]');
    expect((select.element as HTMLSelectElement).value).toBe("");
    expect(select.find("option").text()).toBe("Select...");
    await select.setValue("not_allowed");
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();

    expect(save().attributes("disabled")).toBeUndefined();
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

  it("auto-claim waits for the sign-in check, then claims for a signed-in user", async () => {
    const signedIn = authStore.isSignedIn as unknown as Ref<boolean>;
    let markReady!: () => void;
    vi.mocked(authStore.whenReady).mockReturnValueOnce(
      new Promise<void>((resolve) => {
        markReady = resolve;
      }),
    );
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    vi.mocked(agreements.claimAgreement)
      .mockReset()
      .mockResolvedValue(fakeAgreement());
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();
    expect(agreements.claimAgreement).not.toHaveBeenCalled();

    signedIn.value = true;
    markReady();
    await flushPromises();
    expect(agreements.claimAgreement).toHaveBeenCalledWith(fakeAgreement().id);
    signedIn.value = false;
    wrapper.unmount();
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
    // durationMonths is derived from these dates, not typed -- see fillAllRequired.
    await fillSection(wrapper, "term", {
      startDate: "01/01/2026",
      endDate: "01/12/2026",
    });

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
  });

  it("never writes the new-agreement draft while editing a saved agreement", async () => {
    // The leak this guards: edit AM-1042, then "Create new" for the same template resumed
    // AM-1042's parties and rent from the shared draft slot.
    const wrapper = mount(CaptureForm, {
      props: {
        agreementId: "agr-1",
        initialAgreement: fakeAgreement(),
        state: "IN",
        type: "residential",
      },
    });
    await flushPromises();

    await fillSection(wrapper, "parties", { tenantName: "Tara Sen" });

    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
  });

  it("locks the sections once created, so no edit reaches the preview but not the payment", async () => {
    // Save at one rent, edit to another: the preview and Download PDF would show the edit under the
    // real reference while Finalise and pay charged for the stored draft.
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    const parties = wrapper.find('[data-testid="section-parties"]');
    expect(parties.attributes("disabled")).toBeDefined();
    parties.element.removeAttribute("disabled");
    await parties.trigger("click");

    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(false);
    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
  });

  it("offers an anonymous creator a new agreement as the way to change a locked one", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="locked-notice"]').text()).toContain(
      "can't be changed",
    );
    expect(wrapper.find('[data-testid="edit-saved"]').exists()).toBe(false);
    await wrapper.find('[data-testid="start-new"]').trigger("click");
    expect(wrapper.emitted("change-template")).toHaveLength(1);
  });

  it("offers a signed-in creator Edit agreement once the new agreement is claimed", async () => {
    const signedIn = authStore.isSignedIn as unknown as Ref<boolean>;
    signedIn.value = true;
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    vi.mocked(agreements.claimAgreement)
      .mockReset()
      .mockResolvedValue(fakeAgreement());
    try {
      const wrapper = await mountReady();
      await fillAllRequired(wrapper);
      await wrapper.find('[data-testid="save-continue"]').trigger("click");
      await flushPromises();

      expect(wrapper.find('[data-testid="start-new"]').exists()).toBe(false);
      await wrapper.find('[data-testid="edit-saved"]').trigger("click");
      expect(wrapper.emitted("edit-saved")).toEqual([[fakeAgreement().id]]);
      wrapper.unmount();
    } finally {
      signedIn.value = false;
    }
  });

  it("closes Save & continue after a create so a second click cannot duplicate the agreement", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    const save = () => wrapper.find('[data-testid="save-continue"]');

    await save().trigger("click");
    await flushPromises();
    expect(save().attributes("disabled")).toBeDefined();

    // :disabled is only the first line: with it stripped, the handler must still refuse.
    save().element.removeAttribute("disabled");
    await save().trigger("click");
    await flushPromises();
    expect(mockedCreate).toHaveBeenCalledOnce();
  });

  it("retries only the draft after a failed generate, so a second click does not duplicate the agreement", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockRejectedValueOnce(new Error("render down")).mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    const save = () => wrapper.find('[data-testid="save-continue"]');

    await save().trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="save-error"]').exists()).toBe(true);

    await save().trigger("click");
    await flushPromises();

    expect(mockedCreate).toHaveBeenCalledOnce();
    expect(mockedGenerate).toHaveBeenCalledTimes(2);
    expect(mockedGenerate).toHaveBeenLastCalledWith("agr-1");
    expect(wrapper.find('[data-testid="save-ok"]').exists()).toBe(true);
  });

  it("creates afresh after a failed generate when the terms were changed", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockRejectedValueOnce(new Error("render down")).mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    const save = () => wrapper.find('[data-testid="save-continue"]');

    await save().trigger("click");
    await flushPromises();
    await fillSection(wrapper, "property", { propertyAddress: "14 MG Road" });
    await save().trigger("click");
    await flushPromises();

    expect(mockedCreate).toHaveBeenCalledTimes(2);
  });

  it("Start over, once confirmed, clears the client-held working set and storage", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).not.toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Ready",
    );

    await wrapper.find('[data-testid="reset"]').trigger("click");
    await wrapper.find('[data-testid="confirm-ok"]').trigger("click");
    await flushPromises();

    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Needs input",
    );
  });

  it("Start over, when cancelled, keeps everything typed", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });

    await wrapper.find('[data-testid="reset"]').trigger("click");
    expect(wrapper.find('[data-testid="confirm-dialog"]').exists()).toBe(true);
    await wrapper.find('[data-testid="confirm-cancel"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="confirm-dialog"]').exists()).toBe(false);
    expect(
      localStorage.getItem("am.preview.draft.v1.IN.residential"),
    ).not.toBeNull();
    expect(wrapper.find('[data-testid="status-property"]').text()).toBe(
      "Ready",
    );
  });

  it("offers no Start over while editing a saved agreement", async () => {
    const wrapper = mount(CaptureForm, {
      props: { agreementId: "agr-1", initialAgreement: fakeAgreement() },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="reset"]').exists()).toBe(false);
  });

  it("withdraws Start over once the new agreement is created", async () => {
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    expect(wrapper.find('[data-testid="reset"]').exists()).toBe(true);

    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="reset"]').exists()).toBe(false);
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
    // Next comes the stamp duty step; payment waits for the customer's stamp choice.
    const stampStep = wrapper.findComponent(StampQuoteStep);
    expect(stampStep.exists()).toBe(true);
    expect(mockedPay).not.toHaveBeenCalled();
    stampStep.vm.$emit("confirm", { stampValueMinorUnits: 130000 });
    await flushPromises();
    // ...and the retry actually happens. Finalise is idempotent, so this places no second order.
    expect(mockedFinalise).toHaveBeenCalledWith("agr-1");
    expect(mockedPay).toHaveBeenCalledOnce();
    expect(mockedPay).toHaveBeenCalledWith(
      "agr-1",
      expect.objectContaining({ selection: { stampValueMinorUnits: 130000 } }),
    );
  });

  it("shows the stored terms on the stamp step, not the form's working values", async () => {
    // pre-payment-key-terms-summary: the form says ₹24,000 but the server holds ₹25,000. The lock
    // makes that divergence reachable only through mocks; the point is which source the step reads.
    mockedCreate.mockResolvedValue(fakeAgreement());
    mockedGenerate.mockResolvedValue();
    // The contact step reads the agreement too, so every call resolves the same stored record.
    mockedGetAgreement.mockResolvedValue({
      ...agreementWithContacts(),
      monthlyRent: 25000,
    });

    const wrapper = await mountReady();
    await fillAllRequired(wrapper);
    await fillSection(wrapper, "financial-terms", { monthlyRent: "24000" });
    await wrapper.find('[data-testid="save-continue"]').trigger("click");
    await flushPromises();
    expect(mockedCreate.mock.calls[0][0].monthlyRent).toBe("24000");
    await wrapper.find('[data-testid="finalise-and-pay"]').trigger("click");
    await flushPromises();
    const step = wrapper.findComponent(ContactConfirmation);
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

    const terms = wrapper
      .findComponent(StampQuoteStep)
      .get('[data-testid="key-terms"]');
    expect(terms.get('[data-testid="key-terms-rent"]').text()).toBe("₹25,000");
    expect(terms.text()).not.toContain("24,000");
    expect(mockedGetAgreement).toHaveBeenLastCalledWith("agr-1");
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
    mockedEligible.mockReset();
    mockedEligible.mockResolvedValue(["TG"]);
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
    await new Promise((r) => setTimeout(r, 650)); // debounced (~600ms) preview refresh
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
    await new Promise((r) => setTimeout(r, 650));
    await flushPromises();
    expect(wrapper.find('[data-testid="catalog-pets"]').exists()).toBe(true);
    const [, , afterRemove] = mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(afterRemove).not.toContain("Pets");
  });

  it("the annexure dialog explains one item per line; other sections carry no hint", async () => {
    const schema = schemaWithOptional();
    schema.sections.push({
      title: "Annexure",
      optional: true,
      renderKind: "annexure",
      fields: [
        {
          key: "fixturesInventory",
          label: "Fixtures and inventory schedule",
          widget: "textarea",
          type: "longtext",
          required: false,
          default: "Ceiling fans\nWater meter",
        },
      ],
    });
    mockedGetForm.mockResolvedValue(schema);
    const wrapper = await mountReady();

    await wrapper.find('[data-testid="add-optional-annexure"]').trigger("click");
    await wrapper.find('[data-testid="section-annexure"]').trigger("click");
    expect(wrapper.find('[data-testid="section-hint"]').text()).toMatch(
      /One item per line/,
    );
    const inventory = wrapper.find('[data-testid="field-fixturesInventory"]');
    expect((inventory.element as HTMLTextAreaElement).value).toBe(
      "Ceiling fans\nWater meter",
    );
    await wrapper.find('[data-testid="modal-close"]').trigger("click");

    await wrapper.find('[data-testid="add-optional-pets"]').trigger("click");
    await wrapper.find('[data-testid="section-pets"]').trigger("click");
    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="section-hint"]').exists()).toBe(false);
  });

  it("refuses to save Charges & Utilities under Fixed Amount until an amount is entered", async () => {
    const schema = schemaWithOptional();
    schema.sections.push({
      title: "Charges & Utilities",
      optional: true,
      renderKind: "clauses",
      fields: [
        {
          key: "maintenanceMode",
          label: "How is maintenance handled?",
          widget: "select",
          type: "enum",
          required: false,
          default: "as_billed_by_society",
          options: [
            { value: "included_in_rent", label: "Included In Rent" },
            { value: "fixed_amount", label: "Fixed Amount" },
            { value: "as_billed_by_society", label: "As Billed By Society" },
            { value: "paid_by_owner", label: "Paid By Owner" },
          ],
        },
        {
          key: "maintenanceAmount",
          label: "Maintenance amount (INR / month) – only if Fixed",
          widget: "money",
          type: "money",
          required: false,
        },
      ],
    });
    mockedGetForm.mockResolvedValue(schema);
    const wrapper = await mountReady();

    await wrapper
      .find('[data-testid="add-optional-charges-utilities"]')
      .trigger("click");
    await new Promise((r) => setTimeout(r, 650)); // let the add's own preview refresh settle
    await flushPromises();
    await wrapper.find('[data-testid="section-charges-utilities"]').trigger("click");
    await wrapper
      .find('[data-testid="field-maintenanceMode"]')
      .setValue("fixed_amount");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-maintenanceAmount"]').text(),
    ).toBe("Enter the monthly amount for a fixed maintenance charge.");

    // Blocked: the modal stays open and nothing reaches the preview.
    mockedPreviewHtml.mockClear();
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await new Promise((r) => setTimeout(r, 650));
    await flushPromises();
    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(true);
    expect(mockedPreviewHtml).not.toHaveBeenCalled();

    // Choosing another arrangement clears the error; switching back to Fixed brings it back.
    const mode = wrapper.find('[data-testid="field-maintenanceMode"]');
    await mode.setValue("included_in_rent");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-maintenanceAmount"]').exists(),
    ).toBe(false);
    await mode.setValue("fixed_amount");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-maintenanceAmount"]').exists(),
    ).toBe(true);

    await wrapper.find('[data-testid="field-maintenanceAmount"]').setValue("3500");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-error-maintenanceAmount"]').exists(),
    ).toBe(false);
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await new Promise((r) => setTimeout(r, 650));
    await flushPromises();
    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(false);
    const [sentData] = mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(sentData?.maintenanceMode).toBe("fixed_amount");
    expect(sentData?.maintenanceAmount).toBe("3500");
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
    mockedEligible.mockReset();
    mockedEligible.mockResolvedValue(["TG"]);
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

  it("carries the not-legal-advice notice on the review screen, linking to the terms", async () => {
    // Before this, the only disclaimer on the service was at the foot of the marketing FAQ: the
    // copy was disclaimed and the product was not (docs/LEGAL-POSTURE.md item 2). This is the
    // screen where the customer is looking at the document they are about to commit to.
    const wrapper = mount(CaptureForm, {
      props: { state: "IN", type: "residential" },
    });
    await flushPromises();

    const notice = wrapper.get('[data-testid="legal-disclaimer"]');
    expect(notice.text()).toContain("not a law firm");
    expect(notice.text()).toContain("not legal advice");
    expect(
      wrapper
        .get('[data-testid="legal-disclaimer-terms-link"]')
        .attributes("href"),
    ).toBe("/terms");
  });

  // --- the draft-only jurisdiction disclosure --------------------------------
  //
  // The banner has to be RIGHT, not merely present. It is the one piece of this flow that makes a
  // customer-facing claim about what we can sell them, and a wrong one costs a sale we could have
  // served -- which is why the spec prefers marking nothing to marking wrongly.

  it("marks a draft-only jurisdiction on the capture screen", async () => {
    const wrapper = mount(CaptureForm, {
      props: { state: "IN", type: "residential" },
    });
    await flushPromises();

    const banner = wrapper.get('[data-testid="draft-only-jurisdiction"]');
    expect(banner.text()).toContain("Draft and download only");
    // Honest about what it CAN still do: the template is usable, just not stampable.
    expect(banner.text()).toContain("download it free of charge");
  });

  it("leaves an eligible jurisdiction unmarked on the capture screen", async () => {
    const wrapper = mount(CaptureForm, {
      props: { state: "TG", type: "residential" },
    });
    await flushPromises();

    expect(
      wrapper.find('[data-testid="draft-only-jurisdiction"]').exists(),
    ).toBe(false);
  });

  it("does not mislabel a REOPENED eligible agreement as draft-only", async () => {
    // The regression this guards. props.state falls back to DEFAULT_STATE ("IN"), so before the
    // agreement's own dimensions were carried on AgreementView and passed through by App.vue,
    // every reopened agreement -- a recovery link, a resumed draft, an edit -- rendered this
    // banner, including a perfectly stampable Telangana one.
    const wrapper = mount(CaptureForm, {
      props: {
        agreementId: "agr-1",
        initialAgreement: {
          ...fakeAgreement(),
          state: "TG",
          type: "residential",
        },
        // Deliberately NO state prop, which is exactly how App.vue's edit branch mounted this
        // before the fix: the prop then falls back to DEFAULT_STATE ("IN") and the agreement's
        // real jurisdiction has to come from the agreement itself.
      },
    });
    await flushPromises();

    expect(
      wrapper.find('[data-testid="draft-only-jurisdiction"]').exists(),
    ).toBe(false);
  });

  it("still marks a reopened agreement whose own jurisdiction is draft-only", async () => {
    const wrapper = mount(CaptureForm, {
      props: {
        agreementId: "agr-1",
        initialAgreement: {
          ...fakeAgreement(),
          state: "IN",
          type: "residential",
        },
      },
    });
    await flushPromises();

    expect(
      wrapper.get('[data-testid="draft-only-jurisdiction"]').text(),
    ).toContain("Draft and download only");
  });

  it("marks nothing when eligibility cannot be fetched, rather than warning wrongly", async () => {
    mockedEligible.mockResolvedValue(null);

    const wrapper = mount(CaptureForm, {
      props: { state: "IN", type: "residential" },
    });
    await flushPromises();

    expect(
      wrapper.find('[data-testid="draft-only-jurisdiction"]').exists(),
    ).toBe(false);
  });
});

// ---------------------------------------------------------------------------------------------
// Derived tenancy term + the registration warning.
//
// The bug this covers: the Term section offered "Duration (months)" as an editable box seeded to
// 11, while the server derived the term from the dates and overwrote it at render. A 2026-01-08 to
// 2028-01-08 tenancy previewed as 11 months and SIGNED as 24.
// ---------------------------------------------------------------------------------------------

describe("CaptureForm: derived tenancy term", () => {
  beforeEach(() => {
    mockedGetForm.mockReset();
    mockedEligible.mockReset();
    mockedPreviewHtml.mockReset();
    mockedGetForm.mockResolvedValue(sampleSchema());
    mockedEligible.mockResolvedValue(["TG"]);
    mockedPreviewHtml.mockResolvedValue("<p>preview</p>");
    // The shell resumes a draft from localStorage on mount, so a leftover draft from an earlier
    // test would silently supply dates this test never set.
    localStorage.clear();
  });

  async function openTermWith(startDate: string, endDate: string) {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    if (startDate)
      await wrapper.find('[data-testid="field-startDate"]').setValue(startDate);
    if (endDate)
      await wrapper.find('[data-testid="field-endDate"]').setValue(endDate);
    await flushPromises();
    return wrapper;
  }

  it("shows the duration derived from the dates, not the template default", async () => {
    const wrapper = await openTermWith("08/01/2026", "08/01/2028");

    // The reported case: 24 whole months. Not 11.
    expect(
      wrapper.find('[data-testid="field-durationMonths"]').text(),
    ).toContain("24");
    expect(
      wrapper.find('[data-testid="field-durationMonths"]').text(),
    ).not.toContain("11");
  });

  it("renders the duration as a display, not an input the user can type into", async () => {
    const wrapper = await openTermWith("08/01/2026", "08/01/2028");

    const duration = wrapper.find('[data-testid="field-durationMonths"]');
    // A derived field is not an input at all -- there is nothing to focus or type in.
    expect(duration.element.tagName).not.toBe("INPUT");
    expect(duration.attributes("aria-readonly")).toBe("true");
  });

  it("shows the term as undetermined when only the start date is set", async () => {
    const wrapper = await openTermWith("08/01/2026", "");

    expect(wrapper.find('[data-testid="field-durationMonths"]').text()).toMatch(
      /not yet determined/i,
    );
  });

  it("keeps the derived duration in step as the dates change", async () => {
    const wrapper = await openTermWith("01/01/2026", "01/12/2026");
    expect(
      wrapper.find('[data-testid="field-durationMonths"]').text(),
    ).toContain("11");

    await wrapper.find('[data-testid="field-endDate"]').setValue("01/01/2027");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="field-durationMonths"]').text(),
    ).toContain("12");
  });

  it("never sends the derived duration to the server", async () => {
    const wrapper = await openTermWith("08/01/2026", "08/01/2028");
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await new Promise((r) => setTimeout(r, 650)); // debounced (~600ms) preview refresh
    await flushPromises();

    // The server strips durationMonths as anti-mass-assignment and recomputes it; sending one would
    // only create a second, drifting copy of a value the server owns.
    const [sentData] = mockedPreviewHtml.mock.calls.at(-1) ?? [];
    expect(sentData).not.toHaveProperty("durationMonths");
    expect(sentData).toMatchObject({
      startDate: "2026-01-08",
      endDate: "2028-01-08",
    });
  });
});

describe("CaptureForm: tenancy date range", () => {
  beforeEach(() => {
    mockedGetForm.mockReset();
    mockedEligible.mockReset();
    mockedPreviewHtml.mockReset();
    mockedGetForm.mockResolvedValue(sampleSchema());
    mockedEligible.mockResolvedValue(["TG"]);
    mockedPreviewHtml.mockResolvedValue("<p>preview</p>");
    // The shell resumes a draft from localStorage on mount, so a leftover draft from an earlier
    // test would silently supply dates this test never set.
    localStorage.clear();
  });

  it("reports an end date on or before the start date, against the end field", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    await wrapper
      .find('[data-testid="field-startDate"]')
      .setValue("01/06/2026");
    await wrapper.find('[data-testid="field-endDate"]').setValue("01/01/2026");
    await flushPromises();

    // Pre-empts the 400 the server already returns for this, correcting the user in place.
    expect(wrapper.find('[data-testid="field-error-endDate"]').text()).toMatch(
      /after the start date/i,
    );
    expect(wrapper.find('[data-testid="field-error-startDate"]').exists()).toBe(
      false,
    );
  });

  it("disables the save control while the range is reversed", async () => {
    // The rule used to be cosmetic: the error rendered, Save still worked, the section flipped to
    // complete, and the reversed range reached the preview -- where the server derives a NEGATIVE
    // term. Nothing downstream rejected it, so the deed could state "a term of -4 month(s)".
    //
    // Scope of this assertion: the DISABLED ATTRIBUTE is the whole of what it proves. A click on a
    // disabled button dispatches no event in Vue Test Utils, so asserting "the modal stayed open"
    // afterwards would pass identically with `saveSection`'s early return deleted. That early
    // return is defence-in-depth and has no UI route to reach it today (there is no Enter-to-save
    // handler); its decision logic is `crossFieldErrors`, which is unit-tested directly.
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    await wrapper
      .find('[data-testid="field-startDate"]')
      .setValue("01/06/2026");
    await wrapper.find('[data-testid="field-endDate"]').setValue("01/01/2026");
    await flushPromises();

    expect(
      wrapper.find('[data-testid="modal-save"]').attributes("disabled"),
    ).toBeDefined();
    // And the section is not counted as complete while the range is invalid.
    expect(wrapper.find('[data-testid="status-term"]').text()).toBe(
      "Needs input",
    );
  });

  it("re-enables the save control once the range is corrected", async () => {
    // The complement that makes the disabled assertion meaningful: it must not be permanently
    // disabled, and correcting the range must clear it -- i.e. no dead end for the user.
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    await wrapper
      .find('[data-testid="field-startDate"]')
      .setValue("01/06/2026");
    await wrapper.find('[data-testid="field-endDate"]').setValue("01/01/2026");
    await flushPromises();
    expect(
      wrapper.find('[data-testid="modal-save"]').attributes("disabled"),
    ).toBeDefined();

    await wrapper.find('[data-testid="field-endDate"]').setValue("01/06/2027");
    await flushPromises();

    const save = wrapper.find('[data-testid="modal-save"]');
    expect(save.attributes("disabled")).toBeUndefined();
    await save.trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(false);
  });

  it("still allows saving a part-filled section, so capture stays progressive", async () => {
    // Only CROSS-FIELD errors block. A "required" error must not: a customer is expected to fill
    // a section over more than one visit.
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    await wrapper
      .find('[data-testid="field-startDate"]')
      .setValue("01/01/2026");
    await flushPromises();

    const save = wrapper.find('[data-testid="modal-save"]');
    expect(save.attributes("disabled")).toBeUndefined();

    await save.trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(false);
  });

  it("clears the error once the range is valid", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    await wrapper
      .find('[data-testid="field-startDate"]')
      .setValue("01/06/2026");
    await wrapper.find('[data-testid="field-endDate"]').setValue("01/01/2026");
    await flushPromises();
    expect(wrapper.find('[data-testid="field-error-endDate"]').exists()).toBe(
      true,
    );

    await wrapper.find('[data-testid="field-endDate"]').setValue("01/06/2027");
    await flushPromises();
    expect(wrapper.find('[data-testid="field-error-endDate"]').exists()).toBe(
      false,
    );
  });
});

// ---------------------------------------------------------------------------------------------
// dd/mm/yyyy date entry. The native date input rendered in the host's order (month-first on a
// US-set machine); these pin that what the user sees is what is saved, or the save is refused.
// ---------------------------------------------------------------------------------------------

describe("CaptureForm: dd/mm/yyyy date entry", () => {
  const DRAFT_KEY = "am.preview.draft.v1.IN.residential";

  beforeEach(() => {
    mockedGetForm.mockReset();
    mockedEligible.mockReset();
    mockedPreviewHtml.mockReset();
    mockedGetForm.mockResolvedValue(sampleSchema());
    mockedEligible.mockResolvedValue(["TG"]);
    mockedPreviewHtml.mockResolvedValue("<p>preview</p>");
    localStorage.clear();
  });

  function storedTerm(): Record<string, string> | undefined {
    const draft = JSON.parse(localStorage.getItem(DRAFT_KEY) ?? "{}");
    return draft.data?.term;
  }

  it("refuses to save an edit to an impossible date and keeps neither it nor the old date", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "term", { startDate: "08/01/2026" });
    expect(storedTerm()?.startDate).toBe("2026-01-08");

    await wrapper.find('[data-testid="section-term"]').trigger("click");
    const start = wrapper.find('[data-testid="field-startDate"]');
    await start.trigger("focus");
    await start.setValue("31/02/2026");
    await start.trigger("blur");
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="field-error-startDate"]').text()).toMatch(
      /not a real date/,
    );
    // Not saved: the stored value is untouched, and the field still shows the user's edit rather
    // than having silently reverted to it.
    expect(storedTerm()?.startDate).toBe("2026-01-08");
    expect(
      (start.element as HTMLInputElement).value,
    ).toBe("31/02/2026");
  });

  it("refuses to save an invalid amount, which would otherwise fail only after create", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });

    await wrapper.find('[data-testid="section-financial-terms"]').trigger("click");
    const rent = wrapper.find('[data-testid="field-monthlyRent"]');
    await rent.setValue("1e3");
    await rent.trigger("blur");
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="field-error-monthlyRent"]').text()).toMatch(
      /at most two decimal places/,
    );
    const draft = JSON.parse(localStorage.getItem(DRAFT_KEY) ?? "{}");
    expect(draft.data?.["financial-terms"]?.monthlyRent).toBe("25000");
  });

  it("drops a non-ISO date from a resumed draft", async () => {
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        savedAt: Date.now(),
        data: { term: { startDate: "31/02/2026", endDate: "2026-12-01" } },
      }),
    );
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    expect(
      (wrapper.find('[data-testid="field-startDate"]').element as HTMLInputElement)
        .value,
    ).toBe("");
    expect(
      (wrapper.find('[data-testid="field-endDate"]').element as HTMLInputElement)
        .value,
    ).toBe("01/12/2026");
  });

  it("shows a saved date day-first on the section card", async () => {
    const wrapper = await mountReady();
    await fillSection(wrapper, "term", { startDate: "08/01/2026" });
    expect(wrapper.find('[data-testid="section-term"]').text()).toContain(
      "08/01/2026",
    );
  });

  it("still saves a section with a blank required date, reporting it as required", async () => {
    const wrapper = await mountReady();
    await wrapper.find('[data-testid="section-term"]').trigger("click");
    expect(wrapper.find('[data-testid="field-error-startDate"]').text()).toBe(
      "Start date is required.",
    );
    await wrapper.find('[data-testid="modal-save"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(false);
  });

  it("closes only the picker on Escape, leaving the section open", async () => {
    const wrapper = mount(CaptureForm, { attachTo: document.body });
    await flushPromises();
    try {
      await wrapper.find('[data-testid="section-term"]').trigger("click");
      await wrapper.find('[data-testid="field-startDate"]').setValue("08/01/2026");
      await wrapper
        .find('[data-testid="date-picker-toggle-startDate"]')
        .trigger("click");
      await wrapper.find('[role="grid"]').trigger("keydown", { key: "Escape" });
      await flushPromises();

      expect(wrapper.find('[role="grid"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="section-modal"]').exists()).toBe(true);
      expect(
        (wrapper.find('[data-testid="field-startDate"]').element as HTMLInputElement)
          .value,
      ).toBe("08/01/2026");
    } finally {
      wrapper.unmount();
    }
  });
});
