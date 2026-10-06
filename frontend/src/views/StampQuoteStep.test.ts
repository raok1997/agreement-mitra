import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StampQuoteStep from "./StampQuoteStep.vue";
import * as stampQuote from "../api/stampQuote";
import type { StampQuote } from "../api/stampQuote";

// The stamp duty step (state-stamp-duty-quoting). Every amount it shows must be the server's; the
// recommended option is pre-selected; a below-duty choice cannot be paid for until the warning is
// acknowledged; and what it emits is a choice, never an amount.

vi.mock("../api/stampQuote", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/stampQuote")>();
  return { ...actual, getStampQuote: vi.fn() };
});

const mockedQuote = vi.mocked(stampQuote.getStampQuote);

function quote(over: Partial<StampQuote> = {}): StampQuote {
  return {
    agreementId: "agr-1",
    available: true,
    status: "QUOTABLE",
    frozen: false,
    dutyMinorUnits: 130000,
    currency: "INR",
    breakdown: [
      { kind: "QUANTITY", label: "TOTAL_RENT", amount: "275000", delta: false },
      { kind: "BASE", label: "0.4% of 325000", amount: "1300", delta: false },
    ],
    registrationRequired: true,
    rule: {
      id: "TG-lease-residential",
      legalReference: "Art. 31 -- UNVERIFIED",
      reviewed: false,
    },
    warningVersion: "under-stamp-v1",
    options: [
      {
        stampValueMinorUnits: 130000,
        belowDuty: false,
        recommended: true,
        // Deliberately NOT 499 + 1200: the component must show the server's figure, not compute one.
        totalMinorUnits: 171717,
        medium: "challan",
      },
      {
        stampValueMinorUnits: 10000,
        belowDuty: true,
        recommended: false,
        totalMinorUnits: 49900,
        medium: "stamp-paper",
      },
    ],
    ...over,
  };
}

async function mountStep(q: StampQuote = quote()) {
  mockedQuote.mockResolvedValue(q);
  const wrapper = mount(StampQuoteStep, { props: { agreementId: "agr-1" } });
  await flushPromises();
  return wrapper;
}

describe("StampQuoteStep", () => {
  beforeEach(() => mockedQuote.mockReset());

  it("shows the server's duty, registration notice and totals, with the recommended option pre-selected", async () => {
    const wrapper = await mountStep();

    expect(wrapper.get('[data-testid="stamp-duty"]').text()).toBe("₹1,300.00");
    expect(wrapper.find('[data-testid="registration-notice"]').exists()).toBe(
      true,
    );
    const recommended = wrapper.get('[data-testid="stamp-option-130000"]');
    expect(recommended.text()).toContain("₹1,717.17");
    expect(recommended.text()).toContain("Recommended");
    expect((recommended.get("input").element as HTMLInputElement).checked).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="under-stamp-warning"]').exists()).toBe(
      false,
    );
    expect(
      (
        wrapper.get('[data-testid="stamp-quote-pay"]')
          .element as HTMLButtonElement
      ).disabled,
    ).toBe(false);
  });

  it("emits the recommended choice without an acknowledgement", async () => {
    const wrapper = await mountStep();

    await wrapper.get('[data-testid="stamp-quote-pay"]').trigger("click");

    expect(wrapper.emitted("confirm")?.[0]).toEqual([
      { stampValueMinorUnits: 130000 },
    ]);
  });

  it("keeps a below-duty choice unpayable until the warning is acknowledged", async () => {
    const wrapper = await mountStep();

    await wrapper
      .get('[data-testid="stamp-option-10000"] input')
      .setValue(true);
    const pay = wrapper.get('[data-testid="stamp-quote-pay"]');
    expect(wrapper.find('[data-testid="under-stamp-warning"]').exists()).toBe(
      true,
    );
    expect((pay.element as HTMLButtonElement).disabled).toBe(true);
    await pay.trigger("click");
    expect(wrapper.emitted("confirm")).toBeUndefined();

    await wrapper.get('[data-testid="under-stamp-acknowledge"]').setValue(true);
    expect((pay.element as HTMLButtonElement).disabled).toBe(false);
    await pay.trigger("click");

    expect(wrapper.emitted("confirm")?.[0]).toEqual([
      {
        stampValueMinorUnits: 10000,
        underStampAcknowledgement: { warningVersion: "under-stamp-v1" },
      },
    ]);
  });

  it("names an e-stamp certificate as itself rather than as stamp paper", async () => {
    // The Karnataka shape: an any-amount e-stamp plans the exact duty, so it is the recommended,
    // pre-selected option on every Karnataka quote. An unlabelled medium falls through to the
    // "Stamp paper" default, which would mislabel the most prominent option on the screen.
    const wrapper = await mountStep(
      quote({
        options: [
          {
            stampValueMinorUnits: 170000,
            belowDuty: false,
            recommended: true,
            totalMinorUnits: 219900,
            medium: "e-stamp",
          },
          {
            stampValueMinorUnits: 50000,
            belowDuty: true,
            recommended: false,
            totalMinorUnits: 49900,
            medium: "stamp-paper",
          },
        ],
      }),
    );

    const text = wrapper.text();
    expect(text).toContain("e-Stamp certificate");
    expect(text).toContain("Single stamp paper");
  });

  it("says stamping is unavailable and offers no payment when the quote is not available", async () => {
    const wrapper = await mountStep(
      quote({
        available: false,
        status: "NOT_CHARGEABLE",
        options: [],
        dutyMinorUnits: null,
      }),
    );

    expect(
      wrapper.find('[data-testid="stamp-quote-unavailable"]').text(),
    ).toContain("not yet available for this agreement's jurisdiction");
    expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(
      false,
    );
  });

  it("does not blame the jurisdiction when the duty is merely unplannable", async () => {
    const wrapper = await mountStep(
      quote({
        available: false,
        status: "UNPLANNABLE",
        options: [],
      }),
    );

    const notice = wrapper
      .find('[data-testid="stamp-quote-unavailable"]')
      .text();
    expect(notice).toContain("not available for this agreement yet");
    expect(notice).not.toContain("jurisdiction");
    expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(
      false,
    );
  });

  it("formats the breakdown readably, signs every adjustment and closes with the server's duty", async () => {
    // The Karnataka capped shape: a zero rounding delta used to render as a bare "0" last line,
    // which reads as a total of nothing.
    const wrapper = await mountStep(
      quote({
        dutyMinorUnits: 50000,
        breakdown: [
          { kind: "QUANTITY", label: "AVERAGE_ANNUAL_RENT", amount: "558000", delta: false },
          { kind: "QUANTITY", label: "REFUNDABLE_DEPOSIT", amount: "150000", delta: false },
          {
            kind: "BASE",
            label: "0.5% of 708000 (term 1-12 months)",
            amount: "3540",
            delta: false,
          },
          {
            kind: "SLAB_MAXIMUM",
            label: "maximum 500 for term 1-12 months",
            amount: "-3040",
            delta: true,
          },
          {
            kind: "ROUNDING",
            label: "rounded UP to 1 rupee(s)",
            amount: "0",
            delta: true,
          },
        ],
      }),
    );

    const rows = wrapper.findAll("details li").map((li) => li.text());
    expect(rows).toEqual([
      "Average annual rent₹5,58,000.00",
      "Refundable deposit₹1,50,000.00",
      "0.5% of 7,08,000 (term 1-12 months)₹3,540.00",
      "Maximum 500 for term 1-12 months−₹3,040.00",
      "Rounded up to 1 rupee(s)+₹0.00",
      "Stamp duty₹500.00",
    ]);
  });

  it("names the escalation as an unsigned amount beneath the quantity it moved", async () => {
    const wrapper = await mountStep(
      quote({
        dutyMinorUnits: 100,
        breakdown: [
          { kind: "QUANTITY", label: "AVERAGE_ANNUAL_RENT", amount: "123", delta: false },
          {
            kind: "ESCALATION",
            label: "5% rent escalation every 12 months",
            amount: "3.00",
            delta: false,
          },
          { kind: "BASE", label: "0.5% of 123 (term 12-60 months)", amount: "0.615", delta: false },
          { kind: "ROUNDING", label: "rounded UP to 1 rupee(s)", amount: "0.385", delta: true },
        ],
      }),
    );

    const rows = wrapper.findAll("details li").map((li) => li.text());
    expect(rows[1]).toBe("Includes 5% rent escalation every 12 months₹3.00");
    expect(wrapper.get('[data-testid="breakdown-escalation"]').text()).not.toMatch(/[+−]/);
  });

  it("shows no escalation line when the breakdown has none", async () => {
    const wrapper = await mountStep();

    expect(wrapper.find('[data-testid="breakdown-escalation"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain("escalation");
  });

  it("signs a line by the server's delta flag, not by its kind", async () => {
    const wrapper = await mountStep(
      quote({
        breakdown: [
          { kind: "FUTURE_INFO", label: "an informational fact", amount: "10", delta: false },
          { kind: "FUTURE_ADJUST", label: "an adjustment", amount: "10", delta: true },
        ],
      }),
    );

    const amounts = wrapper.findAll('[data-testid="breakdown-amount"]').map((a) => a.text());
    expect(amounts).toEqual(["₹10.00", "+₹10.00"]);
  });
});
