import { describe, it, expect } from "vitest";
import type { StaffOrderEntry } from "../api/staffOrders";
import {
  agreementPaymentText,
  doubleChargeNote,
  formatOrderMoney,
  nextStep,
  outcomeLabel,
  statusLabel,
} from "./staffOrderFormat";

function entry(over: Partial<StaffOrderEntry> = {}): StaffOrderEntry {
  return {
    trackingReference: "AMOWN00001",
    paymentOrderId: "po-1",
    status: "CREATED",
    amountMinorUnits: 49900,
    currency: "INR",
    createdAt: "2026-10-01T10:00:00Z",
    agreementPayment: "UNPAID",
    stuckOutcomes: ["RECORDING_FAILED"],
    heldBy: [],
    attempts: [],
    ...over,
  };
}

describe("staff order labels", () => {
  it("labels the four stuck outcomes", () => {
    expect(outcomeLabel("RECORDING_FAILED")).toBe("Not recorded");
    expect(outcomeLabel("DUPLICATE_REFERENCE")).toBe("Payment id reused");
    expect(outcomeLabel("AMOUNT_MISMATCH")).toBe("Amount mismatch");
    expect(outcomeLabel("ERRORED")).toBe("Error while recording");
  });

  it("labels an open checkout and an abandoned order", () => {
    expect(statusLabel("CREATED")).toBe("Checkout open");
    expect(statusLabel("EXPIRED")).toBe("Abandoned");
  });

  it("shows a code without a label as itself", () => {
    expect(outcomeLabel("FAILED_AT_GATEWAY")).toBe("FAILED_AT_GATEWAY");
    expect(outcomeLabel("MISSING_REFERENCE")).toBe("MISSING_REFERENCE");
    expect(statusLabel("FAILED")).toBe("FAILED");
    expect(agreementPaymentText("SOMETHING_NEW")).toBe("SOMETHING_NEW");
  });

  it("words each agreement payment state", () => {
    expect(agreementPaymentText("UNPAID")).toBe("Agreement unpaid");
    expect(agreementPaymentText("WAIVED")).toBe("Agreement payment waived");
    expect(agreementPaymentText("PAID_THIS_PAYMENT")).toBe(
      "Agreement paid under this payment",
    );
    expect(agreementPaymentText("PAID_OTHER_PAYMENT")).toBe(
      "Agreement paid under another payment",
    );
  });
});

describe("staff order money", () => {
  it("formats rupees from paise", () => {
    expect(formatOrderMoney(49900, "INR")).toBe("₹499.00");
    expect(formatOrderMoney(4990, "INR")).toBe("₹49.90");
    expect(formatOrderMoney(12000050, "INR")).toBe("₹1,20,000.50");
  });

  it("shows any other currency as the amount followed by the code, without throwing", () => {
    expect(formatOrderMoney(49900, "NOT-A-CODE")).toBe("499.00 NOT-A-CODE");
    expect(formatOrderMoney(49900, "USD")).toBe("499.00 USD");
    expect(formatOrderMoney(49900, "<b>")).toBe("499.00 <b>");
  });

  it("shows the bare amount when the gateway sent no currency", () => {
    expect(formatOrderMoney(49900, null)).toBe("499.00");
  });
});

describe("the next step for a stuck order", () => {
  it("says nothing is to be recorded when the agreement is paid under this payment", () => {
    expect(nextStep(entry({ agreementPayment: "PAID_THIS_PAYMENT" }))).toBe(
      "Already recorded on this agreement. Nothing to record.",
    );
  });

  it("puts paid-under-this-payment ahead of a mismatch and a reused id", () => {
    expect(
      nextStep(
        entry({
          agreementPayment: "PAID_THIS_PAYMENT",
          stuckOutcomes: ["AMOUNT_MISMATCH", "DUPLICATE_REFERENCE"],
        }),
      ),
    ).toBe("Already recorded on this agreement. Nothing to record.");
  });

  it("holds an amount mismatch for a decision, ahead of a reused id", () => {
    expect(
      nextStep(
        entry({
          stuckOutcomes: ["DUPLICATE_REFERENCE", "AMOUNT_MISMATCH"],
          heldBy: ["AM7K2P9Q"],
        }),
      ),
    ).toBe(
      "Amount differs. Do not record until someone decides to refund or to accept.",
    );
  });

  it("names where a reused payment id is recorded", () => {
    expect(
      nextStep(
        entry({ stuckOutcomes: ["DUPLICATE_REFERENCE"], heldBy: ["AM7K2P9Q"] }),
      ),
    ).toBe(
      "This payment is already recorded on AM7K2P9Q. Do not alter the id to get past the refusal.",
    );
  });

  it("does not claim a reused id is recorded elsewhere when nothing holds it now", () => {
    const step = nextStep(entry({ stuckOutcomes: ["DUPLICATE_REFERENCE"] }));
    expect(step).toBe(
      "Refused as already recorded, but nothing holds this payment id now. Confirm the capture in the gateway dashboard, then record it by hand under the payment id shown.",
    );
    expect(step).not.toContain("already recorded on");
  });

  it.each(["WAIVED", "PAID_OTHER_PAYMENT"])(
    "never says to record by hand on an agreement that is %s",
    (agreementPayment) => {
      const settled =
        "The agreement is already settled. Do not record this payment. Check whether it is a double charge before refunding.";
      for (const stuckOutcomes of [
        ["RECORDING_FAILED"],
        ["ERRORED"],
        ["DUPLICATE_REFERENCE"],
      ]) {
        const stuck = entry({ agreementPayment, stuckOutcomes });
        expect(nextStep(stuck)).toBe(settled);
        // The step already says it; a second line would repeat it.
        expect(doubleChargeNote(stuck)).toBeNull();
      }
    },
  );

  it("says 'this agreement' when the holder is the entry's own agreement", () => {
    expect(
      nextStep(
        entry({
          stuckOutcomes: ["DUPLICATE_REFERENCE", "RECORDING_FAILED"],
          heldBy: ["AMOWN00001"],
        }),
      ),
    ).toBe(
      "This payment is already recorded on this agreement. Do not alter the id to get past the refusal.",
    );
  });

  it("has an unrecorded or errored payment confirmed and recorded by hand", () => {
    const step =
      "Confirm the capture in the gateway dashboard, then record it by hand under the payment id shown.";
    expect(nextStep(entry({ stuckOutcomes: ["RECORDING_FAILED"] }))).toBe(step);
    expect(nextStep(entry({ stuckOutcomes: ["ERRORED"] }))).toBe(step);
  });

  it("gives a not-paid entry no next step and no note", () => {
    const notPaid = entry({ stuckOutcomes: [], agreementPayment: "WAIVED" });
    expect(nextStep(notPaid)).toBeNull();
    expect(doubleChargeNote(notPaid)).toBeNull();
  });
});

describe("the double-charge note", () => {
  it("is added beside an amount mismatch on a waived agreement", () => {
    const stuck = entry({
      agreementPayment: "WAIVED",
      stuckOutcomes: ["AMOUNT_MISMATCH"],
    });
    expect(nextStep(stuck)).toContain("Amount differs.");
    expect(doubleChargeNote(stuck)).toBe(
      "The agreement was waived, so this may be a double charge. Check before refunding.",
    );
  });

  it("is added beside a named holder on an agreement paid under another payment", () => {
    const stuck = entry({
      agreementPayment: "PAID_OTHER_PAYMENT",
      stuckOutcomes: ["DUPLICATE_REFERENCE"],
      heldBy: ["AM7K2P9Q"],
    });
    expect(nextStep(stuck)).toContain("already recorded on AM7K2P9Q");
    expect(doubleChargeNote(stuck)).toBe(
      "The agreement is paid under another payment, so this may be a double charge. Check before refunding.",
    );
  });

  it("stays silent for an unpaid agreement and one paid under this payment", () => {
    for (const stuckOutcomes of [["AMOUNT_MISMATCH"], ["RECORDING_FAILED"]]) {
      expect(
        doubleChargeNote(entry({ agreementPayment: "UNPAID", stuckOutcomes })),
      ).toBeNull();
      expect(
        doubleChargeNote(
          entry({ agreementPayment: "PAID_THIS_PAYMENT", stuckOutcomes }),
        ),
      ).toBeNull();
    }
  });
});
