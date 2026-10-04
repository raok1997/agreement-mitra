import { describe, expect, it } from "vitest";
import { AgreementHttpError } from "../api/agreements";
import { CustomerFacingError, ServiceBusyError } from "../api/http";
import { PaymentHttpError } from "../api/payments";
import { StaffQueueHttpError } from "../api/staffQueue";
import { StampQuoteHttpError } from "../api/stampQuote";
import { customerMessage } from "./refusalMessages";

// The allowlist: only an error written for customers shows its own message. Everything else -- an
// API HTTP error, typed or not, whose message is a status string, or a runtime error -- falls back.

const FALLBACK = "Could not start payment. Please try again.";
const TYPE = "urn:agreementmitra:problem:jurisdiction-unsupported";

describe("customerMessage", () => {
  it("shows the load-refusal retry message", () => {
    expect(customerMessage(new ServiceBusyError(7), FALLBACK)).toContain(
      "try again in 7 seconds",
    );
  });

  it("shows a customer-facing error's own message", () => {
    expect(
      customerMessage(
        new CustomerFacingError("Could not load the payment window."),
        FALLBACK,
      ),
    ).toBe("Could not load the payment window.");
  });

  it("recognises a subclass of the marker", () => {
    class Specific extends CustomerFacingError {}
    expect(customerMessage(new Specific("Specific text."), FALLBACK)).toBe(
      "Specific text.",
    );
  });

  it.each([
    ["AgreementHttpError typed", new AgreementHttpError(409, TYPE)],
    ["AgreementHttpError untyped", new AgreementHttpError(500, null)],
    ["PaymentHttpError typed", new PaymentHttpError(409, TYPE)],
    ["PaymentHttpError untyped", new PaymentHttpError(500, null)],
    ["StaffQueueHttpError typed", new StaffQueueHttpError(409, TYPE)],
    ["StaffQueueHttpError untyped", new StaffQueueHttpError(500, null)],
    ["a status-only error", new StampQuoteHttpError(500)],
    ["a plain Error", new Error("x")],
    ["a failed fetch", new TypeError("Failed to fetch")],
    ["an empty customer-facing error", new CustomerFacingError("")],
    ["a non-Error", "boom"],
  ])("falls back for %s, never a status string", (_, e) => {
    const message = customerMessage(e, FALLBACK);
    expect(message).toBe(FALLBACK);
    expect(message).not.toContain("request failed:");
  });
});
