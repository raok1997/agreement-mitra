import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import CaptureForm from "./CaptureForm.vue";
import * as client from "../api/client";
import * as agreements from "../api/agreements";
import * as documentPreview from "../api/documentPreview";
import * as templateForm from "../api/templateForm";
import * as jurisdictions from "../api/jurisdictions";
import * as authStore from "../api/authStore";
import { AGREEMENT_UNAVAILABLE_MESSAGE } from "./refusalMessages";
import type { FormSchema } from "../api/templateForm";
import StampQuoteStep from "./StampQuoteStep.vue";
import ContactConfirmation, {
  type PartyContact,
} from "./ContactConfirmation.vue";

// The REAL throw path of every call these refusals travel through: finaliseAgreement,
// payForAgreement -> startCheckout, updateAgreement and generateAgreementDocument run for real
// against a stubbed fetch that answers with literal server problem bodies. Component tests that
// hand-build a typed error stayed green while production rendered "Agreement request failed: 409";
// these cannot (agreement-error-problem-type-plumbing, design Risks).

vi.mock("../api/cookies", () => ({
  readCookie: (name: string) =>
    name === "__Host-XSRF-TOKEN" ? "csrf-token" : null,
}));
vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, createAgreement: vi.fn() };
});
vi.mock("../api/documentPreview", () => ({
  fetchDocumentPreviewHtml: vi.fn(),
  fetchDocumentPreviewPdf: vi.fn(),
}));
vi.mock("../api/templateForm", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/templateForm")>();
  return { ...actual, getTemplateForm: vi.fn() };
});
vi.mock("../api/authStore", async () => {
  const { ref } = await import("vue");
  return {
    isSignedIn: ref(false),
    whenReady: vi.fn(() => Promise.resolve()),
    reconcile: vi.fn(() => Promise.resolve()),
  };
});
vi.mock("../api/agreements", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/agreements")>();
  return { ...actual, getAgreement: vi.fn(), claimAgreement: vi.fn() };
});
vi.mock("../api/jurisdictions", () => ({ fetchEligibleOrNone: vi.fn() }));
vi.mock("../api/stampQuote", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/stampQuote")>();
  return { ...actual, getStampQuote: vi.fn(() => new Promise(() => {})) };
});

const JURISDICTION = "urn:agreementmitra:problem:jurisdiction-unsupported";
const DRAFT_FROZEN = "urn:agreementmitra:problem:draft-frozen";
const CONTACT_REQUIRED = "urn:agreementmitra:problem:contact-required";
const RENDER_BUSY = "urn:agreementmitra:problem:render-busy";
const NOT_FOUND = "urn:agreementmitra:problem:resource-not-found";

function problem(
  status: number,
  type: string,
  headers: Record<string, string> = {},
) {
  return () =>
    new Response(JSON.stringify({ type, status }), {
      status,
      headers: { "Content-Type": "application/problem+json", ...headers },
    });
}
function json(body: unknown) {
  return () =>
    new Response(JSON.stringify(body), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
}
function nonJson(status: number) {
  return () => new Response("<html>upstream error</html>", { status });
}

const AGREEMENT: client.AgreementView = {
  id: "agr-1",
  trackingNumber: "AM-A5E4D7-010126",
  propertyAddress: "12 MG Road",
  monthlyRent: 25000,
  securityDeposit: 0,
  startDate: "2026-01-01",
  endDate: "2026-12-01",
  durationMonths: 11,
  createdAt: "2026-07-10T00:00:00Z",
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
  ],
};

// One routing table for every case; a case overrides the one route it is about.
type Route = () => Response;
let routes: Record<string, Route>;
function defaultRoutes(): Record<string, Route> {
  return {
    "POST /api/agreements/agr-1/document": () =>
      new Response(null, { status: 204 }),
    "POST /api/agreements/agr-1/finalise": json({
      agreementId: "agr-1",
      trackingReference: "AM-A5E4D7-010126",
      status: "PDF_GENERATED",
    }),
    "PUT /api/agreements/agr-1": json(AGREEMENT),
  };
}

function schema(): FormSchema {
  const text = (key: string, label: string) => ({
    key,
    label,
    widget: "text" as const,
    type: "text" as const,
    required: true,
  });
  return {
    dimensions: { state: "IN", type: "residential" },
    templateId: "rental-base",
    version: 1,
    contentHash: "hash-1",
    sections: [
      {
        title: "Parties",
        fields: [text("tenantName", "Tenant"), text("ownerName", "Owner")],
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
            label: "Start",
            widget: "date",
            type: "date",
            required: true,
          },
          {
            key: "endDate",
            label: "End",
            widget: "date",
            type: "date",
            required: true,
          },
        ],
      },
    ],
  };
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

async function fillAndSave(wrapper: ReturnType<typeof mount>) {
  await fillSection(wrapper, "parties", {
    tenantName: "Tara Sen",
    ownerName: "Asha Rao",
  });
  await fillSection(wrapper, "financial-terms", { monthlyRent: "25000" });
  await fillSection(wrapper, "property", { propertyAddress: "12 MG Road" });
  await fillSection(wrapper, "term", {
    startDate: "01/01/2026",
    endDate: "01/12/2026",
  });
  await wrapper.find('[data-testid="save-continue"]').trigger("click");
  await flushPromises();
}

/** Create, then press "Finalise and pay": the contact step loads the agreement. */
async function openContactStep(): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(CaptureForm);
  await flushPromises();
  await fillAndSave(wrapper);
  await wrapper.find('[data-testid="finalise-and-pay"]').trigger("click");
  await flushPromises();
  return wrapper;
}

/** Confirm the contacts, changed (so PATCH /contacts runs for real) or as loaded. */
async function confirmContacts(
  wrapper: ReturnType<typeof mount>,
  email = "",
): Promise<void> {
  wrapper.findComponent(ContactConfirmation).vm.$emit(
    "confirm",
    AGREEMENT.signers.map((s) => ({
      id: s.id,
      name: s.name,
      role: s.role,
      email: email || (s.email ?? ""),
      mobile: s.mobile ?? "",
    })) as PartyContact[],
  );
  await flushPromises();
}

/** Create, confirm unchanged contacts, choose a stamp value: finalise + payment run for real. */
async function payFromCaptureForm(): Promise<string> {
  const wrapper = await openContactStep();
  await confirmContacts(wrapper);
  wrapper.findComponent(StampQuoteStep).vm.$emit("confirm", {
    stampValueMinorUnits: 130000,
  });
  await flushPromises();
  return wrapper.get('[data-testid="pay-error"]').text();
}

/** Edit an owned agreement and save: updateAgreement + generateAgreementDocument run for real. */
async function saveEdit(): Promise<string> {
  const wrapper = mount(CaptureForm, {
    props: { agreementId: "agr-1", initialAgreement: AGREEMENT },
  });
  await flushPromises();
  await fillAndSave(wrapper);
  return wrapper.get('[data-testid="save-error"]').text();
}

describe("CaptureForm refusals through the real API client", () => {
  beforeEach(() => {
    routes = defaultRoutes();
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string, init: RequestInit = {}) => {
        const key = `${(init.method ?? "GET").toUpperCase()} ${input}`;
        const route = routes[key];
        if (!route) throw new Error(`unrouted request: ${key}`);
        return Promise.resolve(route());
      }),
    );
    vi.mocked(templateForm.getTemplateForm).mockResolvedValue(schema());
    vi.mocked(jurisdictions.fetchEligibleOrNone).mockResolvedValue(["TG"]);
    vi.mocked(client.createAgreement).mockResolvedValue(AGREEMENT);
    vi.mocked(agreements.getAgreement).mockResolvedValue(AGREEMENT);
    vi.mocked(documentPreview.fetchDocumentPreviewHtml).mockResolvedValue(
      "<html><body>preview</body></html>",
    );
    localStorage.clear();
    URL.createObjectURL = vi.fn(() => "blob:stub");
    URL.revokeObjectURL = vi.fn();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(authStore.reconcile).mockClear();
  });

  it("(a) explains a jurisdiction refusal at finalise", async () => {
    routes["POST /api/agreements/agr-1/finalise"] = problem(409, JURISDICTION);

    const message = await payFromCaptureForm();

    expect(message).toContain(
      "not yet available for this agreement's jurisdiction",
    );
    expect(message).not.toContain("request failed:");
    expect(message).not.toContain("try again");
  });

  it("(b) explains the same refusal from the checkout re-gate", async () => {
    routes["POST /api/agreements/agr-1/payment/order"] = problem(
      409,
      JURISDICTION,
    );

    const message = await payFromCaptureForm();

    expect(message).toContain(
      "not yet available for this agreement's jurisdiction",
    );
    expect(message).not.toContain("request failed:");
  });

  it("(c) says the terms are locked when an edit meets the order freeze", async () => {
    routes["PUT /api/agreements/agr-1"] = problem(409, DRAFT_FROZEN);

    const message = await saveEdit();

    expect(message).toContain("order has already been placed");
    expect(message).toContain("can no longer be changed");
    expect(message).not.toContain("request failed:");
  });

  it("(d) falls back to the generic payment message for an untyped server failure", async () => {
    routes["POST /api/agreements/agr-1/finalise"] = nonJson(500);

    const message = await payFromCaptureForm();

    expect(message).toBe("Could not start payment. Please try again.");
  });

  it("(e) falls back to the generic payment message for a typed refusal with no copy", async () => {
    routes["POST /api/agreements/agr-1/payment/order"] = problem(
      409,
      CONTACT_REQUIRED,
    );

    const message = await payFromCaptureForm();

    expect(message).toBe("Could not start payment. Please try again.");
  });

  it("(f) falls back to the generic save message when an edit fails", async () => {
    routes["PUT /api/agreements/agr-1"] = nonJson(500);

    const message = await saveEdit();

    expect(message).toBe("Could not save. Please try again.");
  });

  it("(g) still says when to retry after a rate-limited finalise", async () => {
    routes["POST /api/agreements/agr-1/finalise"] = problem(
      429,
      "urn:agreementmitra:problem:rate-limited",
      { "Retry-After": "7" },
    );

    const message = await payFromCaptureForm();

    expect(message).toContain("try again in 7 seconds");
  });

  it("(h) still says when to retry when the edited document's render is busy", async () => {
    routes["POST /api/agreements/agr-1/document"] = problem(503, RENDER_BUSY, {
      "Retry-After": "7",
    });

    const message = await saveEdit();

    expect(message).toContain("try again in 7 seconds");
  });

  // A 404 on the pay path: the agreement is unknown, or claimed by an account this session is not
  // signed in as (draft-attach-owner-gate D4). One message for both, and the session is re-checked.

  it("(i) says the agreement is not available when the contact step's load is refused", async () => {
    vi.mocked(agreements.getAgreement).mockImplementation(async () => {
      throw await agreements.AgreementHttpError.from(problem(404, NOT_FOUND)());
    });

    const wrapper = await openContactStep();
    const message = wrapper.get('[data-testid="pay-error"]').text();

    expect(message).toBe(AGREEMENT_UNAVAILABLE_MESSAGE);
    expect(message).not.toContain("Could not load the party details");
    expect(authStore.reconcile).toHaveBeenCalled();
  });

  it("(j) says the same when saving the contacts is refused", async () => {
    routes["PATCH /api/agreements/agr-1/contacts"] = problem(404, NOT_FOUND);

    const wrapper = await openContactStep();
    await confirmContacts(wrapper, "changed@example.com");

    expect(wrapper.findComponent(ContactConfirmation).props("error")).toBe(
      AGREEMENT_UNAVAILABLE_MESSAGE,
    );
    expect(authStore.reconcile).toHaveBeenCalled();
  });

  it("(k) says the same when finalise is refused", async () => {
    routes["POST /api/agreements/agr-1/finalise"] = problem(404, NOT_FOUND);

    const message = await payFromCaptureForm();

    expect(message).toBe(AGREEMENT_UNAVAILABLE_MESSAGE);
    expect(message).not.toContain("Could not start payment");
    expect(message).not.toContain("request failed:");
    expect(authStore.reconcile).toHaveBeenCalled();
  });

  it("(l) says the same when the checkout call after finalise is refused", async () => {
    routes["POST /api/agreements/agr-1/payment/order"] = problem(404, NOT_FOUND);

    const message = await payFromCaptureForm();

    expect(message).toBe(AGREEMENT_UNAVAILABLE_MESSAGE);
  });

  it("(m) never describes an account in the not-available message", () => {
    expect(AGREEMENT_UNAVAILABLE_MESSAGE).not.toMatch(/@|\bby\b|owner|claimed/i);
  });
});
