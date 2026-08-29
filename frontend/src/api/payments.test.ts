// Payment client tests. Nothing here touches the network or the provider's CDN: `fetch` is stubbed
// and the checkout window is a fake constructor installed on `window`.
//
// The cases that matter are the unhappy ones. A customer who pays and closes the tab, a dismissed
// window, a lost callback - in every one of them the UI must end up reflecting what the SERVER
// says, never what happened in the page.

import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import {
  PaymentHttpError,
  formatMinorUnits,
  getPaymentProgress,
  payForAgreement,
  reportCheckoutResult,
  startCheckout,
} from "./payments";

const AGREEMENT_ID = "1a111111-2b22-3c33-4d44-5e5555555555";

const SESSION = {
  agreementId: AGREEMENT_ID,
  keyId: "rzp_test_public",
  orderId: "order_TEST1",
  amountMinorUnits: 49900,
  currency: "INR",
  orderStatus: "CREATED",
  paymentState: "UNPAID",
};

function progress(paymentState: string) {
  return {
    agreementId: AGREEMENT_ID,
    paymentState,
    orderStatus: paymentState === "PAID" ? "PAID" : "CREATED",
    amountMinorUnits: 49900,
    currency: "INR",
  };
}

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

/** A stand-in for the provider's checkout window. Drives one scripted outcome and then stops. */
function installCheckout(
  behaviour: (options: Record<string, unknown>) => void,
): {
  opened: number;
  lastOptions: Record<string, unknown> | null;
} {
  const state = {
    opened: 0,
    lastOptions: null as Record<string, unknown> | null,
  };
  const handlers: Record<string, (payload: unknown) => void> = {};
  class FakeRazorpay {
    constructor(public options: Record<string, unknown>) {
      state.lastOptions = options;
    }
    on(event: string, handler: (payload: unknown) => void) {
      handlers[event] = handler;
    }
    open() {
      state.opened += 1;
      behaviour({ ...this.options, __failed: handlers["payment.failed"] });
    }
  }
  (window as unknown as { Razorpay: unknown }).Razorpay = FakeRazorpay;
  return state;
}

const noWait = () => Promise.resolve();

const HANDLER_RESULT = {
  razorpay_order_id: "order_TEST1",
  razorpay_payment_id: "pay_TEST1",
  razorpay_signature: "sig",
};

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (window as unknown as { Razorpay?: unknown }).Razorpay;
});

describe("payment API client", () => {
  it("places the order and returns only public values", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(SESSION));

    const session = await startCheckout(AGREEMENT_ID);

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/agreements/${AGREEMENT_ID}/payment/order`,
      expect.objectContaining({ method: "POST" }),
    );
    expect(session.keyId).toBe("rzp_test_public");
    // The public key id is the ONLY provider credential a browser may ever hold.
    expect(JSON.stringify(session)).not.toMatch(/secret/i);
  });

  it("sends no amount, currency, or discount when starting checkout", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(SESSION));

    await startCheckout(AGREEMENT_ID);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    // No body at all: the amount is the server's calculation and a client cannot influence it.
    expect(init.body).toBeUndefined();
  });

  it("surfaces the HTTP status so a 404 can be told apart from a server error", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 404));

    await expect(startCheckout(AGREEMENT_ID)).rejects.toBeInstanceOf(
      PaymentHttpError,
    );
  });

  it("reports the checkout result as identifiers and a signature only", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(progress("UNPAID")));

    await reportCheckoutResult(AGREEMENT_ID, HANDLER_RESULT);

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    const body = JSON.parse(init.body as string);
    expect(body).toEqual({
      razorpayOrderId: "order_TEST1",
      razorpayPaymentId: "pay_TEST1",
      razorpaySignature: "sig",
    });
    // No amount field exists on this request, so a tampered client has nothing to tamper with.
    expect(Object.keys(body)).toHaveLength(3);
  });

  it("reads payment progress from the server", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(progress("PAID")));

    await expect(getPaymentProgress(AGREEMENT_ID)).resolves.toMatchObject({
      paymentState: "PAID",
    });
  });
});

describe("paying for an agreement", () => {
  it("opens checkout with the public key and the server's order and amount", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION)) // start
      .mockResolvedValue(jsonResponse(progress("PAID"))); // callback + polls
    const checkout = installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
    });

    await payForAgreement(AGREEMENT_ID, { wait: noWait });

    expect(checkout.opened).toBe(1);
    expect(checkout.lastOptions).toMatchObject({
      key: "rzp_test_public",
      order_id: "order_TEST1",
      amount: 49900,
      currency: "INR",
    });
  });

  it("reports PAID only when the SERVER says paid", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValueOnce(jsonResponse(progress("PAID")));
    installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "PAID",
    );
  });

  it("does not claim success when the handler fired but the server has not confirmed", async () => {
    // The single most important negative case. Checkout said "done" in this browser; the server
    // has not seen a webhook. Claiming success here would tell a customer they had paid on the
    // strength of a value that travelled through their own browser.
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValue(jsonResponse(progress("UNPAID")));
    installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
    });

    await expect(
      payForAgreement(AGREEMENT_ID, { wait: noWait, pollAttempts: 3 }),
    ).resolves.toBe("PENDING");
  });

  it("settles on PAID when the webhook lands a moment after the modal closes", async () => {
    // The ordinary race: the browser is back before the webhook is applied. Polling is what makes
    // this settle correctly instead of showing a spurious "not paid".
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValueOnce(jsonResponse(progress("UNPAID"))) // callback
      .mockResolvedValueOnce(jsonResponse(progress("UNPAID"))) // poll 1
      .mockResolvedValueOnce(jsonResponse(progress("PAID"))); // poll 2
    installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
    });

    await expect(
      payForAgreement(AGREEMENT_ID, { wait: noWait, pollAttempts: 4 }),
    ).resolves.toBe("PAID");
  });

  it("treats a lost callback as a reason to keep reading, not as a failure", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockRejectedValueOnce(new Error("network down")) // callback never lands
      .mockResolvedValueOnce(jsonResponse(progress("PAID"))); // the server knows anyway
    installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
    });

    await expect(
      payForAgreement(AGREEMENT_ID, { wait: noWait, pollAttempts: 2 }),
    ).resolves.toBe("PAID");
  });

  it("reports a dismissed window as dismissed, never as success", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValue(jsonResponse(progress("UNPAID")));
    installCheckout((options) => {
      (options.modal as { ondismiss: () => void }).ondismiss();
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "DISMISSED",
    );
  });

  it("still checks the server once after a dismissal, in case the webhook landed", async () => {
    // Closing the window is not proof of not paying: the payment may have completed and the modal
    // been dismissed afterwards.
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValueOnce(jsonResponse(progress("PAID")));
    installCheckout((options) => {
      (options.modal as { ondismiss: () => void }).ondismiss();
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "PAID",
    );
  });

  it("reports a provider-reported failure as failed", async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValue(jsonResponse(progress("UNPAID")));
    installCheckout((options) => {
      (options.__failed as (p: unknown) => void)({ error: { code: "BAD" } });
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "FAILED",
    );
  });

  it("skips checkout entirely when the agreement is already paid", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ ...SESSION, paymentState: "PAID", orderStatus: "PAID" }),
    );
    const checkout = installCheckout(() => {
      throw new Error("checkout must not open for a paid agreement");
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "PAID",
    );
    expect(checkout.opened).toBe(0);
  });

  it("ignores a second outcome from the checkout window", async () => {
    // Providers have been known to fire both a handler and a dismissal. The first one wins; the
    // second must not resolve the attempt again or double-report it.
    fetchMock
      .mockResolvedValueOnce(jsonResponse(SESSION))
      .mockResolvedValue(jsonResponse(progress("PAID")));
    installCheckout((options) => {
      (options.handler as (r: unknown) => void)(HANDLER_RESULT);
      (options.modal as { ondismiss: () => void }).ondismiss();
    });

    await expect(payForAgreement(AGREEMENT_ID, { wait: noWait })).resolves.toBe(
      "PAID",
    );
  });
});

describe("amount display", () => {
  it("formats integer minor units without floating-point arithmetic", () => {
    expect(formatMinorUnits(49900, "INR")).toBe("INR 499.00");
    expect(formatMinorUnits(1, "INR")).toBe("INR 0.01");
    expect(formatMinorUnits(100000, "INR")).toBe("INR 1000.00");
    expect(formatMinorUnits(120050, "INR")).toBe("INR 1200.50");
  });
});
