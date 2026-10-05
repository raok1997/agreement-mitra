import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import AgreementStatus from "./AgreementStatus.vue";
import * as payments from "../api/payments";
import * as signing from "../api/signingProgress";
import * as stampQuote from "../api/stampQuote";
import * as authStore from "../api/authStore";
import { AGREEMENT_UNAVAILABLE_MESSAGE } from "./refusalMessages";
import type { AgreementView } from "../api/client";

// "Complete payment" through the REAL payForAgreement -> startCheckout, against a stubbed fetch that
// answers with the server's literal problem body. A hand-built PaymentHttpError(409) cannot carry a
// type the real client never read, which is how this view came to say "Contact support" for a
// jurisdiction refusal (agreement-error-problem-type-plumbing).

vi.mock("../api/cookies", () => ({
  readCookie: (name: string) =>
    name === "__Host-XSRF-TOKEN" ? "csrf-token" : null,
}));
vi.mock("../api/payments", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/payments")>();
  return { ...actual, getPaymentProgress: vi.fn() };
});
// The order already exists, so its quote is frozen and payment resumes straight into payWith.
vi.mock("../api/stampQuote", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/stampQuote")>();
  return {
    ...actual,
    getStampQuote: vi.fn(async (agreementId: string) => ({
      agreementId,
      available: true,
      status: "QUOTABLE",
      frozen: true,
      dutyMinorUnits: 130000,
      currency: "INR",
      breakdown: [],
      registrationRequired: true,
      rule: {
        id: "TG-lease-residential",
        legalReference: null,
        reviewed: false,
      },
      warningVersion: "under-stamp-v1",
      options: [
        {
          stampValueMinorUnits: 130000,
          belowDuty: false,
          recommended: true,
          totalMinorUnits: 169900,
          medium: "challan",
        },
      ],
    })),
  };
});
vi.mock("../api/signingProgress", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("../api/signingProgress")>();
  return {
    ...actual,
    getSigningProgress: vi.fn(),
    downloadSignedDocument: vi.fn(),
  };
});
vi.mock("../api/authStore", async () => {
  const { ref } = await import("vue");
  return { isSignedIn: ref(false), reconcile: vi.fn(() => Promise.resolve()) };
});

const AGREEMENT = {
  id: "ag-1",
  trackingNumber: "AM3G3VXSAKD",
  propertyAddress: "12 MG Road, Bengaluru",
  monthlyRent: 25000,
  securityDeposit: 50000,
  startDate: "2026-01-01",
  endDate: "2026-12-01",
  durationMonths: 11,
  createdAt: "2026-01-01T00:00:00Z",
  signers: [],
  activeSections: [],
  state: "TG",
  type: "residential",
} as AgreementView;

// The checkout-order refusal each case answers with; a case overrides it before clicking.
let orderRefusal: { type: string; status: number };

function mountAndPay() {
  const wrapper = mount(AgreementStatus, {
    props: {
      agreement: AGREEMENT,
      pollIntervalMs: 20,
      pollWait: () => new Promise<void>(() => {}),
    },
  });
  return flushPromises()
    .then(() => wrapper.get('[data-testid="status-pay"]').trigger("click"))
    .then(() => flushPromises())
    .then(() => wrapper);
}

describe("AgreementStatus refusals through the real payment client", () => {
  beforeEach(() => {
    orderRefusal = {
      type: "urn:agreementmitra:problem:jurisdiction-unsupported",
      status: 409,
    };
    vi.stubGlobal(
      "fetch",
      vi.fn((input: string, init: RequestInit = {}) => {
        const key = `${(init.method ?? "GET").toUpperCase()} ${input}`;
        if (key !== "POST /api/agreements/ag-1/payment/order") {
          throw new Error(`unrouted request: ${key}`);
        }
        return Promise.resolve(
          new Response(JSON.stringify(orderRefusal), {
            status: orderRefusal.status,
            headers: { "Content-Type": "application/problem+json" },
          }),
        );
      }),
    );
    vi.mocked(payments.getPaymentProgress).mockResolvedValue({
      agreementId: "ag-1",
      paymentState: "UNPAID",
      orderStatus: "created",
      amountMinorUnits: 169900,
      currency: "INR",
    });
    vi.mocked(signing.getSigningProgress).mockResolvedValue({
      agreementId: "ag-1",
      status: "IN_PROGRESS",
      stage: "AWAITING_STAMP",
      terminal: false,
      signedDocumentReady: false,
      parties: [],
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.mocked(authStore.reconcile).mockClear();
  });

  it("explains a jurisdiction refusal at checkout instead of sending the customer to support", async () => {
    const wrapper = mount(AgreementStatus, {
      props: {
        agreement: AGREEMENT,
        pollIntervalMs: 20,
        pollWait: () => new Promise<void>(() => {}),
      },
    });
    await flushPromises();

    await wrapper.get('[data-testid="status-pay"]').trigger("click");
    await flushPromises();

    const text = wrapper.text();
    expect(text).toContain(
      "not yet available for this agreement's jurisdiction",
    );
    expect(text).not.toContain("Contact support");
    expect(text).not.toContain("request failed:");
  });

  // draft-attach-owner-gate: a 404 here means this session is not the owner's. Sign in, not support.

  it("says the agreement is not available when the stamp quote is refused 404", async () => {
    vi.mocked(stampQuote.getStampQuote).mockRejectedValueOnce(
      new stampQuote.StampQuoteHttpError(404),
    );

    const text = (await mountAndPay()).text();

    expect(text).toContain(AGREEMENT_UNAVAILABLE_MESSAGE);
    expect(text).not.toContain("Contact support");
    expect(authStore.reconcile).toHaveBeenCalled();
  });

  it("says the same when the checkout order is refused 404 not-found", async () => {
    orderRefusal = {
      type: "urn:agreementmitra:problem:resource-not-found",
      status: 404,
    };

    const text = (await mountAndPay()).text();

    expect(text).toContain(AGREEMENT_UNAVAILABLE_MESSAGE);
    expect(text).not.toContain("Contact support");
    expect(text).not.toContain("request failed:");
    expect(authStore.reconcile).toHaveBeenCalled();
  });
});
