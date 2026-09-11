import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import AgreementStatus from "./AgreementStatus.vue";
import * as payments from "../api/payments";
import * as signing from "../api/signingProgress";
import { LINK_UNAVAILABLE_MESSAGE } from "./linkCopy";
import type { AgreementView } from "../api/client";
import type { PaymentProgress } from "../api/payments";
import type { SigningProgress } from "../api/signingProgress";

// The page behind the emailed link. Every milestone here is a claim about the server's state, so
// each rule in design D2 gets a case; and because the page keeps itself current, the polling rules
// (start / stop / slow / 404) are pinned with an injectable wait so nothing sleeps.

vi.mock("../api/payments", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/payments")>();
  return { ...actual, getPaymentProgress: vi.fn(), payForAgreement: vi.fn() };
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
// Hoisted with the mock: vi.mock factories run before module-level code, so the shared session
// state must be created the same way or the factory sees it before initialisation.
const authState = vi.hoisted(() => ({ session: null as string | null }));
vi.mock("../api/authStore", () => ({
  auth: authState,
  authHeader: () => ({}),
}));

const mockedPayment = vi.mocked(payments.getPaymentProgress);
const mockedPay = vi.mocked(payments.payForAgreement);
const mockedProgress = vi.mocked(signing.getSigningProgress);
const mockedDownload = vi.mocked(signing.downloadSignedDocument);

const OWNER = "aaaaaaaa-0a0a-4aaa-8aaa-aaaaaaaaaaa1";
const TENANT = "bbbbbbbb-0b0b-4bbb-8bbb-bbbbbbbbbbb2";

// Dummy contacts only: the point of these values is to assert they are NOT rendered.
const OWNER_EMAIL = "asha@example.com";
const TENANT_EMAIL = "tara@example.com";
const OWNER_MOBILE = "+91 (dummy) owner-mobile";
const TENANT_MOBILE = "+91 (dummy) tenant-mobile";

function agreement(over: Partial<AgreementView> = {}): AgreementView {
  return {
    id: "ag-1",
    trackingNumber: "AM3G3VXSAKD",
    propertyAddress: "12 MG Road, Bengaluru",
    monthlyRent: 25000,
    securityDeposit: 50000,
    startDate: "2026-01-01",
    endDate: "2026-12-01",
    durationMonths: 11,
    createdAt: "2026-01-01T00:00:00Z",
    signers: [
      {
        id: OWNER,
        name: "Asha Owner",
        firstName: "Asha",
        lastName: "Owner",
        fatherName: "Ravi Owner",
        currentAddress: "1 A St",
        email: OWNER_EMAIL,
        mobile: OWNER_MOBILE,
        role: "OWNER",
      },
      {
        id: TENANT,
        name: "Tara Tenant",
        firstName: "Tara",
        lastName: "Tenant",
        fatherName: "Hari Tenant",
        currentAddress: "3 C St",
        email: TENANT_EMAIL,
        mobile: TENANT_MOBILE,
        role: "TENANT",
      },
    ],
    captureData: { secretField: "do-not-render-me" },
    activeSections: [],
    state: "TG",
    type: "residential",
    ...over,
  } as AgreementView;
}

function payment(over: Partial<PaymentProgress> = {}): PaymentProgress {
  return {
    agreementId: "ag-1",
    paymentState: "PAID",
    orderStatus: "paid",
    amountMinorUnits: 49900,
    currency: "INR",
    ...over,
  };
}
const UNPAID_WITH_ORDER = () =>
  payment({ paymentState: "UNPAID", orderStatus: "created" });

function progress(over: Partial<SigningProgress> = {}): SigningProgress {
  return {
    agreementId: "ag-1",
    status: "IN_PROGRESS",
    stage: "AWAITING_STAMP",
    terminal: false,
    signedDocumentReady: false,
    parties: [
      { signerId: OWNER, role: "OWNER", status: "PENDING" },
      { signerId: TENANT, role: "TENANT", status: "PENDING" },
    ],
    ...over,
  };
}
const signed = (over: Partial<SigningProgress> = {}) =>
  progress({
    stage: "SIGNED",
    terminal: true,
    signedDocumentReady: true,
    parties: [
      { signerId: OWNER, role: "OWNER", status: "SIGNED" },
      { signerId: TENANT, role: "TENANT", status: "SIGNED" },
    ],
    ...over,
  });

/** A wait that never resolves on its own: the test decides when the next tick fires. */
function controlledWait() {
  const pending: Array<() => void> = [];
  const wait = () =>
    new Promise<void>((resolve) => {
      pending.push(resolve);
    });
  const tick = async () => {
    const next = pending.shift();
    if (!next) throw new Error("no poll is waiting");
    next();
    await flushPromises();
  };
  return { wait, tick, pending };
}

function mountWith(
  pay: PaymentProgress,
  prog: SigningProgress,
  opts: {
    wait?: ReturnType<typeof controlledWait>;
    agreement?: AgreementView;
  } = {},
) {
  const wait = opts.wait ?? controlledWait();
  mockedPayment.mockResolvedValue(pay);
  mockedProgress.mockResolvedValue(prog);
  const wrapper = mount(AgreementStatus, {
    props: {
      agreement: opts.agreement ?? agreement(),
      pollIntervalMs: 20,
      pollWait: wait.wait,
    },
  });
  return { wrapper, wait };
}

function condition(
  wrapper: ReturnType<typeof mount>,
  key: string,
): string | undefined {
  return wrapper
    .find(`[data-testid="milestone-${key}"]`)
    .attributes("data-condition");
}

beforeEach(() => {
  mockedPayment.mockReset();
  mockedProgress.mockReset();
  mockedPay.mockReset();
  mockedDownload.mockReset();
  authState.session = null;
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("AgreementStatus — what is shown", () => {
  it("shows the reference, the terms and the parties by name and role only", async () => {
    const { wrapper } = mountWith(payment(), progress());
    await flushPromises();

    expect(wrapper.get('[data-testid="status-reference"]').text()).toBe(
      "AM3G3VXSAKD",
    );
    const terms = wrapper.get('[data-testid="status-terms"]').text();
    expect(terms).toContain("12 MG Road, Bengaluru");
    expect(terms).toContain("25,000");
    expect(terms).toContain("Asha Owner");
    expect(terms).toContain("(Owner)");
    expect(terms).toContain("Tara Tenant");
    expect(terms).toContain("(Tenant)");

    // Never contacts, father's name, address, or the raw capture blob.
    const all = wrapper.text();
    for (const secret of [
      OWNER_EMAIL,
      TENANT_EMAIL,
      OWNER_MOBILE,
      TENANT_MOBILE,
      "Ravi Owner",
      "Hari Tenant",
      "1 A St",
      "3 C St",
      "do-not-render-me",
    ]) {
      expect(all).not.toContain(secret);
    }
  });

  it("renders the term as calendar dates, not UTC instants shifted by the local zone", async () => {
    const { wrapper } = mountWith(payment(), progress());
    await flushPromises();
    const terms = wrapper.get('[data-testid="status-terms"]').text();
    // Whatever zone the test runs in, 2026-01-01 is 1 January and 2026-12-01 is 1 December.
    expect(terms).toMatch(/1 Jan 2026/);
    expect(terms).toMatch(/1 Dec 2026/);
    expect(terms).not.toContain("Dec 2025");
    expect(terms).not.toContain("30 Nov");
  });

  it("labels a signer without a role as Party, consistently with the timeline", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({
        stage: "OUT_FOR_SIGNATURE",
        parties: [{ signerId: OWNER, role: null, status: "PENDING" }],
      }),
      {
        agreement: agreement({
          signers: [{ ...agreement().signers[0], role: null as never }],
        }),
      },
    );
    await flushPromises();
    expect(
      wrapper.get(`[data-testid="status-party-${OWNER}"]`).text(),
    ).toContain("(Party)");
    expect(wrapper.get(`[data-testid="milestone-${OWNER}"]`).text()).toContain(
      "(Party)",
    );
  });

  it("never offers an edit", async () => {
    const { wrapper } = mountWith(payment(), progress());
    await flushPromises();
    expect(wrapper.text().toLowerCase()).not.toContain("edit");
  });
});

describe("AgreementStatus — milestones (design D2)", () => {
  it("paid and awaiting the stamp: e-stamp is current, no party is current", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({ stage: "AWAITING_STAMP" }),
    );
    await flushPromises();
    expect(condition(wrapper, "drafted")).toBe("done");
    expect(condition(wrapper, "paid")).toBe("done");
    expect(condition(wrapper, "stamp")).toBe("current");
    expect(condition(wrapper, OWNER)).toBe("pending");
    expect(condition(wrapper, TENANT)).toBe("pending");
    expect(condition(wrapper, "completed")).toBe("pending");
  });

  it("order placed but unpaid: payment is current and e-stamp is not reached", async () => {
    const { wrapper } = mountWith(
      UNPAID_WITH_ORDER(),
      progress({ stage: "AWAITING_STAMP" }),
    );
    await flushPromises();
    expect(condition(wrapper, "paid")).toBe("current");
    expect(condition(wrapper, "stamp")).toBe("pending");
    expect(wrapper.find('[data-testid="status-pay"]').exists()).toBe(true);
  });

  it("does not invite payment for an agreement whose pipeline has ended", async () => {
    const { wrapper } = mountWith(UNPAID_WITH_ORDER(), signed());
    await flushPromises();
    expect(condition(wrapper, "paid")).toBe("pending");
    expect(wrapper.find('[data-testid="status-pay"]').exists()).toBe(false);
  });

  it("out for signature with the first party signed: the second is current", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({
        stage: "OUT_FOR_SIGNATURE",
        parties: [
          { signerId: OWNER, role: "OWNER", status: "SIGNED" },
          { signerId: TENANT, role: "TENANT", status: "PENDING" },
        ],
      }),
    );
    await flushPromises();
    expect(condition(wrapper, "stamp")).toBe("done");
    expect(condition(wrapper, OWNER)).toBe("done");
    expect(condition(wrapper, TENANT)).toBe("current");
  });

  it("out for signature with nobody signed: only the first in order is current", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({ stage: "OUT_FOR_SIGNATURE" }),
    );
    await flushPromises();
    expect(condition(wrapper, OWNER)).toBe("current");
    expect(condition(wrapper, TENANT)).toBe("pending");
  });

  it("stamp failed is failed, not done -- no ordinal 'past' comparison", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({ stage: "STAMP_FAILED", terminal: true }),
    );
    await flushPromises();
    expect(condition(wrapper, "stamp")).toBe("failed");
    expect(wrapper.text()).toContain("could not be applied");
  });

  it("a rejection names the party and halts the rest", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({
        stage: "FAILED",
        terminal: true,
        parties: [
          { signerId: OWNER, role: "OWNER", status: "REJECTED" },
          { signerId: TENANT, role: "TENANT", status: "PENDING" },
        ],
      }),
    );
    await flushPromises();
    expect(condition(wrapper, OWNER)).toBe("failed");
    expect(wrapper.text()).toContain("Asha Owner (Owner) declined to sign");
    expect(condition(wrapper, TENANT)).toBe("pending");
    expect(wrapper.text()).toContain("Signing halted");
    expect(condition(wrapper, "stamp")).toBe("done");
  });

  it("expired is stated", async () => {
    const { wrapper } = mountWith(
      payment(),
      progress({
        stage: "EXPIRED",
        terminal: true,
        parties: [
          { signerId: OWNER, role: "OWNER", status: "SIGNED" },
          { signerId: TENANT, role: "TENANT", status: "EXPIRED" },
        ],
      }),
    );
    await flushPromises();
    expect(condition(wrapper, TENANT)).toBe("failed");
    expect(wrapper.text()).toContain("signing window expired");
  });

  it("signed: everything done and completed", async () => {
    const { wrapper } = mountWith(payment(), signed());
    await flushPromises();
    expect(condition(wrapper, "completed")).toBe("done");
  });
});

describe("AgreementStatus — payment is the server's word", () => {
  it("does not mark paid when the checkout returned but the server still says unpaid", async () => {
    const unpaid = UNPAID_WITH_ORDER();
    const { wrapper } = mountWith(unpaid, progress());
    await flushPromises();
    mockedPay.mockResolvedValue("PENDING");
    mockedPayment.mockResolvedValue(unpaid);

    await wrapper.get('[data-testid="status-pay"]').trigger("click");
    await flushPromises();

    expect(mockedPay).toHaveBeenCalledWith("ag-1", expect.anything());
    expect(condition(wrapper, "paid")).toBe("current");
  });

  it("marks paid once the server says so after the attempt", async () => {
    const { wrapper } = mountWith(UNPAID_WITH_ORDER(), progress());
    await flushPromises();
    mockedPay.mockResolvedValue("PAID");
    mockedPayment.mockResolvedValue(payment());

    await wrapper.get('[data-testid="status-pay"]').trigger("click");
    await flushPromises();

    expect(condition(wrapper, "paid")).toBe("done");
  });

  it("a slow older poll cannot roll a fresh PAID back to unpaid", async () => {
    const { wrapper, wait } = mountWith(UNPAID_WITH_ORDER(), progress());
    await flushPromises();

    // The poll tick's payment read is left hanging while the customer pays.
    let releaseStale: (v: PaymentProgress) => void = () => {};
    mockedPayment.mockImplementationOnce(
      () =>
        new Promise<PaymentProgress>((resolve) => {
          releaseStale = resolve;
        }),
    );
    await wait.tick();

    mockedPay.mockResolvedValue("PAID");
    mockedPayment.mockResolvedValue(payment());
    await wrapper.get('[data-testid="status-pay"]').trigger("click");
    await flushPromises();
    expect(condition(wrapper, "paid")).toBe("done");

    releaseStale(UNPAID_WITH_ORDER());
    await flushPromises();
    expect(condition(wrapper, "paid")).toBe("done");
  });

  it("stops reading payment once it is settled", async () => {
    const { wait } = mountWith(payment(), progress());
    await flushPromises();
    expect(mockedPayment).toHaveBeenCalledTimes(1);
    await wait.tick();
    await wait.tick();
    expect(mockedPayment).toHaveBeenCalledTimes(1);
    expect(mockedProgress).toHaveBeenCalledTimes(3);
  });

  it("shows a generic message when payment cannot be started", async () => {
    const { wrapper } = mountWith(UNPAID_WITH_ORDER(), progress());
    await flushPromises();
    mockedPay.mockRejectedValue(new payments.PaymentHttpError(409));

    await wrapper.get('[data-testid="status-pay"]').trigger("click");
    await flushPromises();

    expect(wrapper.text()).toContain("Payment cannot be started yet");
  });
});

describe("AgreementStatus — the signed document", () => {
  it("offers the download only when the server says the document is ready, via the API helper", async () => {
    const { wrapper } = mountWith(payment(), signed());
    await flushPromises();
    mockedDownload.mockResolvedValue();

    await wrapper.get('[data-testid="status-download"]').trigger("click");
    await flushPromises();

    // The helper is what carries the session header; the view never builds a URL.
    expect(mockedDownload).toHaveBeenCalledWith(
      "ag-1",
      "AM3G3VXSAKD-signed.pdf",
    );
    expect(wrapper.find('a[href*="signed-document"]').exists()).toBe(false);
  });

  it("says the document is being prepared while it is not ready", async () => {
    const { wrapper } = mountWith(
      payment(),
      signed({ signedDocumentReady: false }),
    );
    await flushPromises();
    expect(wrapper.find('[data-testid="status-download"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.find('[data-testid="status-document-preparing"]').exists(),
    ).toBe(true);
  });
});

describe("AgreementStatus — keeping current (design D3)", () => {
  it("polls while in flight and stops on a terminal stage", async () => {
    const { wrapper, wait } = mountWith(
      payment(),
      progress({ stage: "AWAITING_STAMP" }),
    );
    await flushPromises();
    expect(mockedProgress).toHaveBeenCalledTimes(1);
    expect(wait.pending).toHaveLength(1); // a poll is scheduled

    mockedProgress.mockResolvedValue(progress({ stage: "OUT_FOR_SIGNATURE" }));
    await wait.tick();
    expect(mockedProgress).toHaveBeenCalledTimes(2);
    expect(condition(wrapper, "stamp")).toBe("done");

    mockedProgress.mockResolvedValue(signed());
    await wait.tick();
    expect(mockedProgress).toHaveBeenCalledTimes(3);
    expect(wait.pending).toHaveLength(0); // nothing further scheduled
  });

  it("keeps polling a signed agreement until its document is ready", async () => {
    const { wait } = mountWith(
      payment(),
      signed({ signedDocumentReady: false }),
    );
    await flushPromises();
    expect(wait.pending).toHaveLength(1);

    mockedProgress.mockResolvedValue(signed());
    await wait.tick();
    expect(wait.pending).toHaveLength(0);
  });

  it("does not poll a draft with no order and no payment", async () => {
    const { wait } = mountWith(
      payment({ paymentState: "UNPAID", orderStatus: null }),
      progress({ stage: "NOT_STARTED" }),
    );
    await flushPromises();
    expect(wait.pending).toHaveLength(0);
  });

  it("polls a finalised but unpaid agreement", async () => {
    const { wait } = mountWith(
      UNPAID_WITH_ORDER(),
      progress({ stage: "AWAITING_STAMP" }),
    );
    await flushPromises();
    expect(wait.pending).toHaveLength(1);
  });

  it("stops and shows the shared unavailable message when the progress re-read is refused", async () => {
    const { wrapper, wait } = mountWith(payment(), progress());
    await flushPromises();

    mockedProgress.mockRejectedValue(new signing.SigningProgressHttpError(404));
    await wait.tick();

    expect(wrapper.find('[data-testid="status-claimed"]').exists()).toBe(true);
    expect(wrapper.text()).toContain(LINK_UNAVAILABLE_MESSAGE);
    expect(wait.pending).toHaveLength(0);

    await wrapper.get('[data-testid="status-claimed"] button').trigger("click");
    expect(wrapper.emitted("sign-in")).toHaveLength(1);
  });

  it("treats a payment 404 the same way, whichever read settles first", async () => {
    const { wrapper, wait } = mountWith(UNPAID_WITH_ORDER(), progress());
    await flushPromises();

    mockedPayment.mockRejectedValue(new payments.PaymentHttpError(404));
    mockedProgress.mockRejectedValue(new signing.SigningProgressHttpError(404));
    await wait.tick();

    expect(wrapper.find('[data-testid="status-claimed"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-error"]').exists()).toBe(false);
    expect(wait.pending).toHaveLength(0);
  });

  it("shows the unavailable message on first load too, and no sign-in button for a signed-in viewer", async () => {
    authState.session = "s-1";
    const wait = controlledWait();
    mockedPayment.mockRejectedValue(new payments.PaymentHttpError(404));
    mockedProgress.mockResolvedValue(progress());
    const wrapper = mount(AgreementStatus, {
      props: {
        agreement: agreement(),
        pollIntervalMs: 20,
        pollWait: wait.wait,
      },
    });
    await flushPromises();

    expect(wrapper.find('[data-testid="status-claimed"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="status-claimed"] button').exists()).toBe(
      false,
    );
    expect(wait.pending).toHaveLength(0);
  });

  it("keeps the last good state when only one read fails transiently, and keeps polling", async () => {
    const { wrapper, wait } = mountWith(UNPAID_WITH_ORDER(), progress());
    await flushPromises();

    mockedPayment.mockResolvedValue(payment());
    mockedProgress.mockRejectedValue(new signing.SigningProgressHttpError(503));
    await wait.tick();

    expect(condition(wrapper, "paid")).toBe("done"); // the successful read landed
    expect(wrapper.find('[data-testid="status-timeline"]').exists()).toBe(true);
    expect(wait.pending).toHaveLength(1); // backed off, not stopped
  });

  it("stops polling when unmounted", async () => {
    const { wrapper, wait } = mountWith(payment(), progress());
    await flushPromises();
    expect(wait.pending).toHaveLength(1);
    wrapper.unmount();
    await wait.tick(); // the pending wait resolves, but the loop is dead: no further read
    expect(mockedProgress).toHaveBeenCalledTimes(1);
  });
});
