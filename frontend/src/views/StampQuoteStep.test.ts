import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import StampQuoteStep from "./StampQuoteStep.vue";
import * as stampQuote from "../api/stampQuote";
import type { StampQuote } from "../api/stampQuote";
import * as agreements from "../api/agreements";
import { AgreementHttpError } from "../api/agreements";
import type { AgreementView, PartyView } from "../api/client";
import { PROBLEM } from "../api/problems";
import { AGREEMENT_UNAVAILABLE_MESSAGE } from "./refusalMessages";

// The stamp duty step (state-stamp-duty-quoting). Every amount it shows must be the server's; the
// recommended option is pre-selected; a below-duty choice cannot be paid for until the warning is
// acknowledged; and what it emits is a choice, never an amount.

vi.mock("../api/stampQuote", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/stampQuote")>();
  return { ...actual, getStampQuote: vi.fn() };
});

vi.mock("../api/agreements", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/agreements")>();
  return { ...actual, getAgreement: vi.fn() };
});

vi.mock("../api/authStore", () => ({ reconcile: vi.fn(async () => {}) }));

const mockedQuote = vi.mocked(stampQuote.getStampQuote);
const mockedAgreement = vi.mocked(agreements.getAgreement);

function party(over: Partial<PartyView>): PartyView {
  return {
    id: "p",
    name: "",
    firstName: "",
    lastName: "",
    fatherName: "Fathername Zeta",
    currentAddress: "77 Elsewhere Lane, Mysuru",
    email: "secret.party@example.test",
    mobile: "9000012345",
    role: "OWNER",
    ...over,
  };
}

// Every field the step must NOT show carries a value distinctive enough to search the HTML for.
function stored(over: Partial<AgreementView> = {}): AgreementView {
  return {
    id: "agr-1",
    trackingNumber: "AMTRACK0001",
    propertyAddress: "12 MG Road\nBengaluru 560001",
    monthlyRent: 25000,
    securityDeposit: 100000,
    startDate: "2026-11-01",
    endDate: "2027-10-31",
    durationMonths: 12,
    createdAt: "2026-10-01T10:00:00Z",
    signers: [
      party({ id: "o", name: "Ravi Kumar", role: "OWNER" }),
      party({ id: "t", name: "Asha Rao", role: "TENANT" }),
      party({ id: "x", name: "Mohan Das", role: null as unknown as "OWNER" }),
    ],
    captureData: { "lease.lockInMonths": "CAPTUREMARKER" },
    activeSections: [],
    state: "KA",
    type: "residential",
    ...over,
  };
}

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

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
  beforeEach(() => {
    mockedQuote.mockReset();
    mockedAgreement.mockReset();
    mockedAgreement.mockResolvedValue(stored());
  });

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

  describe("key terms (pre-payment-key-terms-summary)", () => {
    it("shows the stored terms, the server's term in months and every party's role", async () => {
      const wrapper = await mountStep();
      const terms = wrapper.get('[data-testid="key-terms"]');

      expect(mockedAgreement).toHaveBeenCalledWith("agr-1");
      expect(terms.text()).toContain("You are paying to stamp and sign this");
      expect(terms.get('[data-testid="key-terms-address"]').text()).toBe(
        "12 MG Road\nBengaluru 560001",
      );
      expect(terms.get('[data-testid="key-terms-rent"]').text()).toBe("₹25,000");
      expect(terms.get('[data-testid="key-terms-deposit"]').text()).toBe(
        "₹1,00,000",
      );
      expect(terms.get('[data-testid="key-terms-term"]').text()).toMatch(
        /01\/11\/2026 to\s+31\/10\/2027 ·\s+12 months/,
      );
      const parties = terms
        .findAll('[data-testid="key-terms-parties"] li')
        .map((li) => li.text());
      expect(parties).toEqual([
        "Ravi Kumar (Owner)",
        "Asha Rao (Tenant)",
        "Mohan Das (Party)",
      ]);
    });

    it("says 1 month in the singular, from the server's figure", async () => {
      mockedAgreement.mockResolvedValue(stored({ durationMonths: 1 }));
      const wrapper = await mountStep();

      expect(wrapper.get('[data-testid="key-terms-term"]').text()).toMatch(
        /· 1 month$/,
      );
    });

    it("never shows a party's contact, address, father's name or captured values", async () => {
      const html = (await mountStep()).html();

      for (const hidden of [
        "secret.party@example.test",
        "9000012345",
        "77 Elsewhere Lane",
        "Fathername Zeta",
        "CAPTUREMARKER",
        "AMTRACK0001",
      ]) {
        expect(html).not.toContain(hidden);
      }
    });

    it("keeps payment disabled while the terms load, then enables it", async () => {
      const pending = deferred<AgreementView>();
      mockedAgreement.mockReturnValue(pending.promise);
      const wrapper = await mountStep();

      expect(wrapper.get('[data-testid="key-terms-loading"]').text()).toBe(
        "Loading the saved terms…",
      );
      const pay = () => wrapper.get('[data-testid="stamp-quote-pay"]');
      expect(pay().attributes("disabled")).toBeDefined();
      await pay().trigger("click");
      expect(wrapper.emitted("confirm")).toBeUndefined();

      pending.resolve(stored());
      await flushPromises();

      expect(wrapper.find('[data-testid="key-terms-loading"]').exists()).toBe(
        false,
      );
      expect(wrapper.get('[data-testid="key-terms-rent"]').text()).toBe("₹25,000");
      expect(pay().attributes("disabled")).toBeUndefined();
    });

    it("offers no stamp option or payment when the terms cannot be read, but keeps Back", async () => {
      mockedAgreement.mockRejectedValue(new AgreementHttpError(500));
      const wrapper = await mountStep();

      const error = wrapper.get('[data-testid="key-terms-error"]');
      expect(error.attributes("role")).toBe("alert");
      expect(error.text()).toBe(
        "Could not load the saved terms. Please try again.",
      );
      expect(wrapper.text()).not.toContain("500");
      expect(wrapper.find('[data-testid="stamp-option-130000"]').exists()).toBe(
        false,
      );
      expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(
        false,
      );

      await wrapper.get("button").trigger("click");
      expect(wrapper.emitted("cancel")).toHaveLength(1);
    });

    it("reads a not-found refusal as the agreement being unavailable, with no status", async () => {
      mockedAgreement.mockRejectedValue(
        new AgreementHttpError(404, PROBLEM.notFound),
      );
      const wrapper = await mountStep();

      expect(wrapper.get('[data-testid="key-terms-error"]').text()).toBe(
        AGREEMENT_UNAVAILABLE_MESSAGE,
      );
      expect(wrapper.text()).not.toContain("404");
    });

    it("treats a response missing a key term as a failure", async () => {
      mockedAgreement.mockResolvedValue({
        ...stored(),
        securityDeposit: undefined,
      } as unknown as AgreementView);
      const wrapper = await mountStep();

      expect(wrapper.find('[data-testid="key-terms-error"]').exists()).toBe(true);
      expect(wrapper.find('[data-testid="stamp-quote-pay"]').exists()).toBe(
        false,
      );
    });

    it("treats a record with no parties, or a blank name, as a failure", async () => {
      mockedAgreement.mockResolvedValue(stored({ signers: [] }));
      const empty = await mountStep();
      expect(empty.find('[data-testid="key-terms-error"]').exists()).toBe(true);
      expect(empty.find('[data-testid="stamp-quote-pay"]').exists()).toBe(false);

      mockedAgreement.mockResolvedValue(
        stored({ signers: [party({ name: "  ", role: "OWNER" })] }),
      );
      const blank = await mountStep();
      expect(blank.find('[data-testid="key-terms-error"]').exists()).toBe(true);
    });

    it("shows paise in a stored rent instead of rounding them away", async () => {
      mockedAgreement.mockResolvedValue(stored({ monthlyRent: 15000.5 }));
      const wrapper = await mountStep();

      expect(wrapper.get('[data-testid="key-terms-rent"]').text()).toBe(
        "₹15,000.50",
      );
    });

    it("says an unavailable agreement once, not 'try again' beside it, when both reads are refused", async () => {
      mockedAgreement.mockRejectedValue(
        new AgreementHttpError(404, PROBLEM.notFound),
      );
      mockedQuote.mockRejectedValue(new stampQuote.StampQuoteHttpError(404));
      const wrapper = mount(StampQuoteStep, { props: { agreementId: "agr-1" } });
      await flushPromises();

      const alerts = wrapper.findAll('[role="alert"]').map((a) => a.text());
      expect(alerts).toEqual([AGREEMENT_UNAVAILABLE_MESSAGE]);
      expect(wrapper.text()).not.toContain("try again");
    });

    it("sends the customer back to fix a wrong term and offers no way to edit it here", async () => {
      const wrapper = await mountStep();

      expect(wrapper.get('[data-testid="key-terms-go-back"]').text()).toBe(
        "If anything here is wrong, don't pay. Go back.",
      );
      const terms = wrapper.get('[data-testid="key-terms"]');
      expect(terms.findAll("input, textarea, select, button")).toHaveLength(0);
      expect(wrapper.text()).not.toMatch(/edit agreement/i);
    });
  });

  describe("maintenance line (maintenance-charge-basis)", () => {
    const CHARGES = "Charges & Utilities";

    function withCharges(
      captureData: Record<string, string>,
      over: Partial<AgreementView> = {},
    ): AgreementView {
      return stored({ captureData, activeSections: [CHARGES], ...over });
    }

    async function line(view: AgreementView) {
      mockedAgreement.mockResolvedValue(view);
      const wrapper = await mountStep();
      const text = wrapper.find('[data-testid="key-terms-maintenance"]');
      const total = wrapper.find('[data-testid="key-terms-maintenance-total"]');
      return {
        wrapper,
        text: text.exists() ? text.text() : null,
        total: total.exists() ? total.text() : null,
      };
    }

    it("shows a fixed amount and the rent-and-maintenance figure", async () => {
      const shown = await line(
        withCharges({ maintenanceMode: "fixed_amount", maintenanceAmount: "3500" }),
      );
      expect(shown.text).toBe("₹3,500 a month, paid to the owner with the rent");
      expect(shown.total).toBe("₹28,500");
      expect(shown.wrapper.get('[data-testid="key-terms"]').text()).toContain(
        "Rent and maintenance together each month",
      );
      expect(shown.wrapper.get('[data-testid="key-terms"]').text()).not.toMatch(
        /\btotal\b/i,
      );
    });

    it("shows paise exactly, summed in whole paise", async () => {
      const shown = await line(
        withCharges(
          { maintenanceMode: "fixed_amount", maintenanceAmount: "3500.50" },
          { monthlyRent: 25000.0 },
        ),
      );
      expect(shown.text).toBe("₹3,500.50 a month, paid to the owner with the rent");
      expect(shown.total).toBe("₹28,500.50");
    });

    it("sums a rent with paise without floating-point drift", async () => {
      const shown = await line(
        withCharges(
          { maintenanceMode: "fixed_amount", maintenanceAmount: "0.20" },
          { monthlyRent: 0.1 },
        ),
      );
      expect(shown.total).toBe("₹0.30");
    });

    it("shows each other arrangement without a total", async () => {
      const expected: Record<string, string> = {
        included_in_rent: "Included in the rent",
        as_billed_by_society: "Paid by the tenant to the society, as billed",
        paid_by_owner: "Paid by the owner",
      };
      for (const [mode, text] of Object.entries(expected)) {
        const shown = await line(
          withCharges({ maintenanceMode: mode, maintenanceAmount: "3500" }),
        );
        expect(shown.text, mode).toBe(text);
        expect(shown.total, mode).toBeNull();
        expect(shown.wrapper.html(), mode).not.toContain("3,500");
      }
    });

    it("reads an absent mode as the default, as billed by the society", async () => {
      const shown = await line(withCharges({}));
      expect(shown.text).toBe("Paid by the tenant to the society, as billed");
      expect(shown.total).toBeNull();
    });

    it("still states a fixed arrangement whose positive amount cannot be read, with no figure", async () => {
      for (const amount of ["1e3", "3500.555"]) {
        const shown = await line(
          withCharges({ maintenanceMode: "fixed_amount", maintenanceAmount: amount }),
        );
        expect(shown.text, amount).toBe(
          "A fixed monthly charge, paid to the owner with the rent",
        );
        expect(shown.total, amount).toBeNull();
      }
    });

    it("shows no line for a fixed mode whose amount the deed does not print", async () => {
      // Both Fixed clauses are gated `maintenanceAmount > 0`, so the deed says nothing here.
      for (const amount of ["", "0", "0.00", "-5"]) {
        const shown = await line(
          withCharges({ maintenanceMode: "fixed_amount", maintenanceAmount: amount }),
        );
        expect(shown.text, amount).toBeNull();
        expect(shown.total, amount).toBeNull();
      }
    });

    it("shows no line for an agreement pinned before the mode existed", async () => {
      // A v7 agreement stores maintenanceBorneBy; its deed says who bears it, which the default
      // ("as billed") would contradict.
      for (const borneBy of ["owner", "tenant"]) {
        const shown = await line(
          withCharges({ maintenanceBorneBy: borneBy, maintenanceAmount: "2500" }),
        );
        expect(shown.text, borneBy).toBeNull();
        expect(shown.total, borneBy).toBeNull();
      }
    });

    it("shows no line without the section, for an unknown mode, or for a commercial agreement", async () => {
      const cases: AgreementView[] = [
        stored({
          captureData: { maintenanceMode: "fixed_amount", maintenanceAmount: "3500" },
          activeSections: [],
        }),
        stored({
          captureData: { maintenanceMode: "fixed_amount", maintenanceAmount: "3500" },
          activeSections: null,
        }),
        withCharges({ maintenanceMode: "shared" }),
        withCharges({ camBorneBy: "tenant" }, { type: "commercial" }),
      ];
      for (const view of cases) {
        const shown = await line(view);
        expect(shown.text).toBeNull();
        expect(shown.total).toBeNull();
      }
    });

    it("from a real stored payload, shows only the listed terms plus the maintenance line", async () => {
      // Mirrors GET /api/agreements/{id}: captureData holds every captured key, party details included.
      const shown = await line(
        withCharges({
          ownerName: "Ravi Kumar",
          ownerFatherName: "CAPTUREFATHER",
          ownerAddress: "CAPTUREADDRESS",
          tenantName: "Asha Rao",
          propertyType: "apartment",
          utilitiesBorneBy: "tenant",
          latePaymentPenalty: "777",
          gracePeriodDays: "5",
          maintenanceMode: "fixed_amount",
          maintenanceAmount: "3500",
        }),
      );
      const html = shown.wrapper.html();
      for (const hidden of [
        "CAPTUREFATHER",
        "CAPTUREADDRESS",
        "777",
        "secret.party@example.test",
        "9000012345",
        "Fathername Zeta",
      ]) {
        expect(html).not.toContain(hidden);
      }
      const labels = shown.wrapper
        .findAll('[data-testid="key-terms"] dt')
        .map((dt) => dt.text());
      expect(labels).toEqual([
        "Property",
        "Monthly rent",
        "Security deposit",
        "Maintenance",
        "Rent and maintenance together each month",
        "Term",
        "Parties",
      ]);
    });
  });
});
