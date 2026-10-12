import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StaffOrders from "./StaffOrders.vue";
import * as staffOrders from "../api/staffOrders";
import type {
  StaffOrderAttempt,
  StaffOrderEntry,
  StaffOrders as Orders,
} from "../api/staffOrders";

// Component tests for the staff Orders tab. It is a read: the assertions that matter are what an
// entry says, that nothing in it can act on an order, and that gateway-supplied text stays text.
vi.mock("../api/staffOrders", async () => {
  const actual =
    await vi.importActual<typeof staffOrders>("../api/staffOrders");
  return { ...actual, fetchStaffOrders: vi.fn() };
});

const mockedFetch = vi.mocked(staffOrders.fetchStaffOrders);

function attempt(over: Partial<StaffOrderAttempt> = {}): StaffOrderAttempt {
  return {
    source: "WEBHOOK",
    outcome: "RECORDING_FAILED",
    providerPaymentId: "pay_Abc123",
    amountMinorUnits: 49900,
    currency: "INR",
    refusedRule: null,
    sqlState: null,
    firstReceivedAt: "2026-10-01T10:00:00Z",
    lastReceivedAt: "2026-10-01T10:00:00Z",
    attemptCount: 1,
    ...over,
  };
}

function entry(over: Partial<StaffOrderEntry> = {}): StaffOrderEntry {
  return {
    trackingReference: "AMSTUCK0001",
    paymentOrderId: "po-stuck",
    status: "CREATED",
    amountMinorUnits: 49900,
    currency: "INR",
    createdAt: "2026-10-01T09:00:00Z",
    agreementPayment: "UNPAID",
    stuckOutcomes: ["RECORDING_FAILED"],
    heldBy: [],
    attempts: [attempt()],
    ...over,
  };
}

function openCheckout(over: Partial<StaffOrderEntry> = {}): StaffOrderEntry {
  return entry({
    trackingReference: "AMOPEN00001",
    paymentOrderId: "po-open",
    stuckOutcomes: [],
    attempts: [],
    ...over,
  });
}

function orders(
  stuck: StaffOrderEntry[],
  notPaid: StaffOrderEntry[] = [],
  truncated = false,
): Orders {
  return {
    stuck: { entries: stuck, truncated: false },
    notPaid: { entries: notPaid, truncated },
  };
}

async function mounted(response: Orders) {
  mockedFetch.mockResolvedValue(response);
  const wrapper = mount(StaffOrders);
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  mockedFetch.mockReset();
});

describe("StaffOrders", () => {
  it("shows the stuck group first and an open checkout under Not paid", async () => {
    const wrapper = await mounted(orders([entry()], [openCheckout()]));

    const groups = wrapper.findAll("h2").map((heading) => heading.text());
    expect(groups).toEqual(["Paid but stuck", "Not paid"]);
    const stuck = wrapper.find('[data-testid="orders-stuck"]');
    expect(stuck.find('[data-testid="order-po-stuck"]').exists()).toBe(true);
    expect(stuck.text()).toContain("AMSTUCK0001");
    expect(stuck.text()).toContain("Not recorded");
    const notPaid = wrapper.find('[data-testid="orders-not-paid"]');
    expect(
      notPaid
        .find('[data-testid="order-po-open"] [data-testid="order-status"]')
        .text(),
    ).toBe("Checkout open");
  });

  it("shows an amount mismatch with both amounts and how often it was seen", async () => {
    const wrapper = await mounted(
      orders([
        entry({
          stuckOutcomes: ["AMOUNT_MISMATCH"],
          attempts: [
            attempt({
              outcome: "AMOUNT_MISMATCH",
              amountMinorUnits: 4990,
              attemptCount: 3,
            }),
          ],
        }),
      ]),
    );

    const row = wrapper.find('[data-testid="order-po-stuck"]');
    expect(row.find('[data-testid="order-outcome"]').text()).toBe(
      "Amount mismatch: order ₹499.00, paid ₹49.90",
    );
    expect(row.find('[data-testid="order-attempt"]').text()).toContain(
      "seen 3 times",
    );
  });

  it("says when the agreement is paid under this payment", async () => {
    const wrapper = await mounted(
      orders([entry({ agreementPayment: "PAID_THIS_PAYMENT" })]),
    );

    expect(wrapper.find('[data-testid="order-agreement-payment"]').text()).toBe(
      "Agreement paid under this payment",
    );
    expect(wrapper.find('[data-testid="order-next-step"]').text()).toContain(
      "Already recorded on this agreement. Nothing to record.",
    );
  });

  it("shows a status and an outcome without a label as their codes", async () => {
    const wrapper = await mounted(
      orders(
        [],
        [
          openCheckout({
            status: "FAILED",
            attempts: [attempt({ outcome: "FAILED_AT_GATEWAY" })],
          }),
        ],
      ),
    );

    const row = wrapper.find('[data-testid="order-po-open"]');
    expect(row.find('[data-testid="order-status"]').text()).toBe("FAILED");
    expect(row.find('[data-testid="order-attempt"]').text()).toContain(
      "FAILED_AT_GATEWAY",
    );
  });

  it("states the next step with the holder on a stuck entry and none on a not-paid entry", async () => {
    const wrapper = await mounted(
      orders(
        [
          entry({
            stuckOutcomes: ["DUPLICATE_REFERENCE"],
            heldBy: ["AM7K2P9Q"],
            attempts: [attempt({ outcome: "DUPLICATE_REFERENCE" })],
          }),
        ],
        [openCheckout({ agreementPayment: "WAIVED" })],
      ),
    );

    expect(
      wrapper
        .find('[data-testid="order-po-stuck"] [data-testid="order-next-step"]')
        .text(),
    ).toContain("This payment is already recorded on AM7K2P9Q.");
    const notPaid = wrapper.find('[data-testid="order-po-open"]');
    expect(notPaid.find('[data-testid="order-next-step"]').exists()).toBe(
      false,
    );
    expect(notPaid.find('[data-testid="order-double-charge"]').exists()).toBe(
      false,
    );
  });

  it("tells staff not to record a payment on an agreement paid under another payment", async () => {
    const wrapper = await mounted(
      orders([entry({ agreementPayment: "PAID_OTHER_PAYMENT" })]),
    );

    const step = wrapper.find('[data-testid="order-next-step"]').text();
    expect(step).toContain("The agreement is already settled.");
    expect(step).toContain("double charge before refunding");
    expect(step).not.toContain("record it by hand");
  });

  it("adds the double-charge note beside an amount mismatch on a waived agreement", async () => {
    const wrapper = await mounted(
      orders([
        entry({
          agreementPayment: "WAIVED",
          stuckOutcomes: ["AMOUNT_MISMATCH"],
          attempts: [attempt({ outcome: "AMOUNT_MISMATCH" })],
        }),
      ]),
    );

    expect(
      wrapper.find('[data-testid="order-double-charge"]').text(),
    ).toContain("may be a double charge. Check before refunding.");
  });

  it("shows the holder of a reused payment id even when the next step is the amount mismatch", async () => {
    const wrapper = await mounted(
      orders(
        [
          entry({
            stuckOutcomes: ["AMOUNT_MISMATCH", "DUPLICATE_REFERENCE"],
            heldBy: ["AM7K2P9Q"],
            attempts: [
              attempt({ outcome: "AMOUNT_MISMATCH", amountMinorUnits: 4990 }),
              attempt({ outcome: "DUPLICATE_REFERENCE" }),
            ],
          }),
        ],
        [openCheckout()],
      ),
    );

    const stuck = wrapper.find('[data-testid="order-po-stuck"]');
    expect(stuck.find('[data-testid="order-next-step"]').text()).toContain(
      "Amount differs.",
    );
    expect(stuck.find('[data-testid="order-held-by"]').text()).toContain(
      "AM7K2P9Q",
    );
    expect(
      wrapper
        .find('[data-testid="order-po-open"] [data-testid="order-held-by"]')
        .exists(),
    ).toBe(false);
  });

  it("offers no control that acts on an order, anywhere on the tab", async () => {
    const wrapper = await mounted(orders([entry()], [openCheckout()]));

    expect(
      wrapper.findAll("button, a, input, select, textarea, form"),
    ).toHaveLength(0);
  });

  it("names the runbook section and says who is not listed", async () => {
    const wrapper = await mounted(orders([entry()]));

    expect(wrapper.find('[data-testid="orders-runbook"]').text()).toContain(
      "A captured payment that was refused or not recorded",
    );
    expect(wrapper.find('[data-testid="orders-runbook"]').text()).toContain(
      "docs/DEPLOYMENT.md",
    );
    expect(wrapper.find('[data-testid="orders-not-listed"]').text()).toContain(
      "confirmed or waived by hand has no payment order and is not listed",
    );
  });

  it("shows markup in a payment id literally and creates no element from it", async () => {
    const hostile = "<img src=x onerror=alert(1)>";
    const wrapper = await mounted(
      orders([entry({ attempts: [attempt({ providerPaymentId: hostile })] })]),
    );

    expect(wrapper.find('[data-testid="attempt-payment-id"]').text()).toBe(
      hostile,
    );
    expect(wrapper.find("img").exists()).toBe(false);
  });

  it("shows an unknown currency as amount then code and still shows the other entries", async () => {
    const wrapper = await mounted(
      orders(
        [entry({ attempts: [attempt({ currency: "NOT-A-CODE" })] })],
        [openCheckout()],
      ),
    );

    expect(wrapper.find('[data-testid="order-attempt"]').text()).toContain(
      "499.00 NOT-A-CODE",
    );
    expect(wrapper.find('[data-testid="order-po-open"]').exists()).toBe(true);
  });

  it("says when a group was cut short", async () => {
    const wrapper = await mounted(orders([], [openCheckout()], true));

    expect(
      wrapper.find('[data-testid="orders-not-paid-truncated"]').text(),
    ).toContain("More orders were left out");
    expect(
      wrapper.find('[data-testid="orders-stuck-truncated"]').exists(),
    ).toBe(false);
  });

  it("says so when a group is empty", async () => {
    const wrapper = await mounted(orders([], []));

    expect(wrapper.find('[data-testid="orders-stuck-empty"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="orders-not-paid-empty"]').exists()).toBe(
      true,
    );
  });

  it("shows a loading state until the list arrives", async () => {
    mockedFetch.mockReturnValue(new Promise(() => {}));

    const wrapper = mount(StaffOrders);
    await flushPromises();

    expect(wrapper.find('[data-testid="orders-loading"]').exists()).toBe(true);
  });

  it("reports a failed load and shows no entry", async () => {
    mockedFetch.mockRejectedValue(new staffOrders.StaffOrdersHttpError(500));

    const wrapper = mount(StaffOrders);
    await flushPromises();

    expect(wrapper.find('[data-testid="orders-error"]').text()).toContain(
      "The orders could not be loaded",
    );
    expect(wrapper.find('[data-testid^="order-po-"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="orders-stuck"]').exists()).toBe(false);
  });

  it("tells a non-staff caller the console is for staff", async () => {
    mockedFetch.mockRejectedValue(new staffOrders.StaffOrdersHttpError(403));

    const wrapper = mount(StaffOrders);
    await flushPromises();

    expect(wrapper.find('[data-testid="orders-error"]').text()).toBe(
      "This console is for AgreementMitra staff.",
    );
  });
});
