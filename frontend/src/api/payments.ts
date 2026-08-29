// Gateway payment for an agreement: place the order server-side, open the provider's hosted
// checkout, and then let the SERVER tell us whether the money actually arrived.
//
// THE RULE THIS MODULE IS BUILT AROUND: the checkout handler firing in this browser proves nothing.
// The tab can be closed before it runs, the callback can be lost, the network can drop. Payment is
// settled by a verified webhook on the server, so every path here ends by READING the server's
// payment state rather than believing what happened in the page. The UI never claims success on the
// strength of a local event.
//
// No secret lives here. The only provider credential the browser ever sees is the PUBLIC key id,
// handed over by the create-order endpoint; the API key secret and the webhook secret are
// server-only and appear nowhere in this file, this bundle, or any build output.
//
// Card, UPI, netbanking and wallet details are collected entirely inside the provider's hosted
// checkout. They never pass through our code, so nothing here can accept, proxy, store, or log one.

import { authHeader } from "./authStore";

const BASE = "/api";

/** Where the provider's Checkout script lives. Loaded lazily, never vendored into our bundle. */
const CHECKOUT_SCRIPT_URL = "https://checkout.razorpay.com/v1/checkout.js";

/** What the browser needs to open checkout. Public values only - there is no field for a secret. */
export interface CheckoutSession {
  agreementId: string;
  /** The provider's PUBLIC key identifier. */
  keyId: string;
  orderId: string;
  /** Integer minor units (paise). Server-calculated; display only. */
  amountMinorUnits: number;
  currency: string;
  orderStatus: string;
  paymentState: string;
}

/** Where payment stands, as the server sees it. The only thing the UI is allowed to trust. */
export interface PaymentProgress {
  agreementId: string;
  paymentState: string;
  orderStatus: string | null;
  amountMinorUnits: number | null;
  currency: string | null;
}

/** An Error carrying the HTTP status so callers can distinguish 404 (not yours) from the rest. */
export class PaymentHttpError extends Error {
  constructor(public readonly status: number) {
    super(`Payment request failed: ${status}`);
    this.name = "PaymentHttpError";
  }
}

/**
 * Place (or resume) the order. Idempotent server-side: reloading the payment page returns the same
 * outstanding order rather than accumulating a new one for every refresh.
 */
export async function startCheckout(
  agreementId: string,
): Promise<CheckoutSession> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/payment/order`, {
    method: "POST",
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new PaymentHttpError(res.status);
  return res.json();
}

/** Read the server's payment state. This is what settles the UI, not anything that happened here. */
export async function getPaymentProgress(
  agreementId: string,
): Promise<PaymentProgress> {
  const res = await fetch(`${BASE}/agreements/${agreementId}/payment`, {
    headers: { ...authHeader() },
  });
  if (!res.ok) throw new PaymentHttpError(res.status);
  return res.json();
}

/** What the hosted checkout handed back. Identifiers and a signature - never an amount. */
export interface CheckoutResult {
  razorpay_order_id: string;
  razorpay_payment_id: string;
  razorpay_signature: string;
}

/**
 * Report what checkout returned. The server verifies the signature and may re-read the order from
 * the provider - it will NOT mark the agreement paid on the strength of this call. The response is
 * simply the current payment state.
 */
export async function reportCheckoutResult(
  agreementId: string,
  result: CheckoutResult,
): Promise<PaymentProgress> {
  const res = await fetch(
    `${BASE}/agreements/${agreementId}/payment/callback`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...authHeader() },
      body: JSON.stringify({
        razorpayOrderId: result.razorpay_order_id,
        razorpayPaymentId: result.razorpay_payment_id,
        razorpaySignature: result.razorpay_signature,
      }),
    },
  );
  if (!res.ok) throw new PaymentHttpError(res.status);
  return res.json();
}

// --- the provider's hosted checkout ----------------------------------------

interface RazorpayInstance {
  open(): void;
  on(event: string, handler: (payload: unknown) => void): void;
}

type RazorpayConstructor = new (
  options: Record<string, unknown>,
) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

/**
 * Load the provider's Checkout script on demand. Deliberately NOT vendored: it is the provider's
 * own payment surface and has to be theirs. Cached by the browser after the first load, and by the
 * `window.Razorpay` check here within a session.
 */
export async function loadCheckoutScript(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return window.Razorpay;
  await new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(
      `script[src="${CHECKOUT_SCRIPT_URL}"]`,
    );
    if (existing) {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () =>
        reject(new Error("Could not load the payment window.")),
      );
      return;
    }
    const script = document.createElement("script");
    script.src = CHECKOUT_SCRIPT_URL;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () =>
      reject(new Error("Could not load the payment window."));
    document.head.appendChild(script);
  });
  if (!window.Razorpay) throw new Error("Could not load the payment window.");
  return window.Razorpay;
}

/**
 * How a payment attempt ended, from the UI's point of view.
 *
 * - `PAID` - the SERVER says paid. The only outcome that may be shown as success.
 * - `PENDING` - the customer went through checkout but the server has not confirmed yet. Not a
 *   failure: the webhook may simply be a moment behind, and reconciliation is the backstop.
 * - `DISMISSED` - the customer closed the window without paying.
 * - `FAILED` - the provider reported the payment failed.
 */
export type PaymentOutcome = "PAID" | "PENDING" | "DISMISSED" | "FAILED";

export interface PayOptions {
  /** Shown in the provider's checkout window. Non-PII. */
  name?: string;
  description?: string;
  /** How many times to re-read the server's payment state before settling on PENDING. */
  pollAttempts?: number;
  /** Delay between reads, in ms. */
  pollIntervalMs?: number;
  /** Injectable for tests, so no test ever waits on a real timer. */
  wait?: (ms: number) => Promise<void>;
}

const DEFAULT_POLL_ATTEMPTS = 6;
const DEFAULT_POLL_INTERVAL_MS = 1500;

const realWait = (ms: number) =>
  new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * Run one payment attempt end to end.
 *
 * Whatever happens in the checkout window, the outcome is decided by re-reading the server. A
 * customer who pays and closes the tab still ends up `PAID` - possibly on a later read, possibly
 * via reconciliation - and a dismissed window is still checked once, because the webhook may have
 * landed while the modal was open.
 */
export async function payForAgreement(
  agreementId: string,
  options: PayOptions = {},
): Promise<PaymentOutcome> {
  const session = await startCheckout(agreementId);
  if (session.paymentState === "PAID") return "PAID";

  const Razorpay = await loadCheckoutScript();
  const wait = options.wait ?? realWait;

  const settled = await new Promise<
    | { kind: "handled"; result: CheckoutResult }
    | { kind: "dismissed" | "failed" }
  >((resolve) => {
    let done = false;
    const finish = (
      value:
        | { kind: "handled"; result: CheckoutResult }
        | { kind: "dismissed" | "failed" },
    ) => {
      if (done) return;
      done = true;
      resolve(value);
    };
    const checkout = new Razorpay({
      key: session.keyId,
      order_id: session.orderId,
      amount: session.amountMinorUnits,
      currency: session.currency,
      name: options.name ?? "AgreementMitra",
      description: options.description ?? "Rental agreement",
      handler: (result: CheckoutResult) => finish({ kind: "handled", result }),
      modal: { ondismiss: () => finish({ kind: "dismissed" }) },
    });
    checkout.on("payment.failed", () => finish({ kind: "failed" }));
    checkout.open();
  });

  if (settled.kind === "handled") {
    // Best effort: the server treats this as a hint and may re-read the order. A failure here is
    // not a failed payment, so it must not be surfaced as one - fall through to polling.
    try {
      const progress = await reportCheckoutResult(agreementId, settled.result);
      if (progress.paymentState === "PAID") return "PAID";
    } catch {
      // ignore - the poll below is the authority
    }
  }

  // Re-read the server. On a dismissal this is a single check (the webhook may have landed while
  // the window was open); after checkout it is a short poll, because the webhook is usually a
  // moment behind the browser.
  const attempts =
    settled.kind === "handled"
      ? (options.pollAttempts ?? DEFAULT_POLL_ATTEMPTS)
      : 1;
  const interval = options.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  for (let i = 0; i < attempts; i++) {
    if (i > 0) await wait(interval);
    try {
      const progress = await getPaymentProgress(agreementId);
      if (progress.paymentState === "PAID") return "PAID";
    } catch {
      // A read that fails is not evidence of non-payment; keep trying, then report honestly.
    }
  }

  if (settled.kind === "handled") return "PENDING";
  return settled.kind === "failed" ? "FAILED" : "DISMISSED";
}

/** Minor units (paise) to a rupee string for display. Integer arithmetic only - never a float. */
export function formatMinorUnits(
  amountMinorUnits: number,
  currency: string,
): string {
  const whole = Math.trunc(amountMinorUnits / 100);
  const fraction = Math.abs(amountMinorUnits % 100)
    .toString()
    .padStart(2, "0");
  return `${currency} ${whole}.${fraction}`;
}
