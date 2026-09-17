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
      { kind: "QUANTITY", label: "TOTAL_RENT", amount: "275000" },
      { kind: "BASE", label: "0.4% of 325000", amount: "1300" },
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

    expect(wrapper.get('[data-testid="stamp-duty"]').text()).toBe(
      "INR 1300.00",
    );
    expect(wrapper.find('[data-testid="registration-notice"]').exists()).toBe(
      true,
    );
    const recommended = wrapper.get('[data-testid="stamp-option-130000"]');
    expect(recommended.text()).toContain("INR 1717.17");
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
      wrapper.find('[data-testid="stamp-quote-unavailable"]').exists(),
    ).toBe(true);
    expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(
      false,
    );
  });
});
