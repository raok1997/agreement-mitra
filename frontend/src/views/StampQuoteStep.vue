<script setup lang="ts">
// The stamp duty step before payment (state-stamp-duty-quoting).
//
// WHAT IT SHOWS. The legal stamp duty the server computed for this agreement, how it was computed,
// whether registration is required, and the stamp values the customer may buy -- each with the
// total they will pay. The recommended option (at or above the duty) is pre-selected.
//
// A VALUE BELOW THE DUTY IS ALLOWED, BUT NEVER SILENTLY. Choosing a single stamp paper below the duty
// reveals the under-stamping warning and keeps the pay button disabled until the customer ticks it.
// The server enforces the same rule and records the acknowledgement; this screen only makes sure the
// customer meets it here rather than as a refusal.
//
// WHAT IS BEING PAID FOR. Above the price, the agreement's key terms as the server has them stored,
// read afresh on every mount -- never from a parent's copy, the capture form or the browser draft
// (pre-payment-key-terms-summary). Payment waits for them; if they cannot be read, the step cannot
// be paid from. Only the shown fields are kept: no party contact, address or captured value.
//
// NOTHING HERE IS COMPUTED IN THE BROWSER. Every amount -- duty, stamp value, total -- is rendered
// exactly as the server sent it. The emitted selection names a choice, never an amount to charge.

import { computed, onMounted, ref } from "vue";
import { formatMinorUnits } from "../api/payments";
import { busyMessage } from "../api/http";
import { getAgreement } from "../api/agreements";
import type { AgreementView } from "../api/client";
import {
  AGREEMENT_UNAVAILABLE_MESSAGE,
  agreementUnavailable,
  JURISDICTION_UNSUPPORTED_MESSAGE,
  STAMP_UNPLANNABLE_MESSAGE,
} from "./refusalMessages";
import { formatRupees, roleLabel } from "./agreementListFormat";
import { formatIso } from "./dateEntry";
import {
  getStampQuote,
  selectionFor,
  type StampQuote,
  type StampQuoteLine,
  type StampQuoteOption,
  type StampSelection,
} from "../api/stampQuote";

const props = defineProps<{
  agreementId: string;
  /** Set while the parent is starting payment, so the button shows progress and stays disabled. */
  busy?: boolean;
  /** Surfaced from the parent when starting payment failed. */
  error?: string | null;
}>();

const emit = defineEmits<{
  (e: "confirm", selection: StampSelection): void;
  (e: "cancel"): void;
}>();

const quote = ref<StampQuote | null>(null);
const loading = ref(true);
const loadError = ref<string | null>(null);
const selectedValue = ref<number | null>(null);
const acknowledged = ref(false);

/** The only fields of the stored agreement this step keeps (design D3). */
interface KeyTerms {
  propertyAddress: string;
  monthlyRent: number;
  securityDeposit: number;
  startDate: string;
  endDate: string;
  durationMonths: number;
  parties: { name: string; role: string }[];
}

const terms = ref<KeyTerms | null>(null);
const termsError = ref<string | null>(null);

function keyTerms(a: AgreementView): KeyTerms {
  const complete =
    typeof a.propertyAddress === "string" &&
    typeof a.monthlyRent === "number" &&
    typeof a.securityDeposit === "number" &&
    typeof a.startDate === "string" &&
    typeof a.endDate === "string" &&
    typeof a.durationMonths === "number" &&
    Array.isArray(a.signers) &&
    a.signers.length > 0 &&
    a.signers.every((p) => typeof p?.name === "string" && p.name.trim() !== "");
  if (!complete) throw new Error("incomplete agreement");
  return {
    propertyAddress: a.propertyAddress,
    monthlyRent: a.monthlyRent,
    securityDeposit: a.securityDeposit,
    startDate: a.startDate,
    endDate: a.endDate,
    durationMonths: a.durationMonths,
    parties: a.signers.map((p) => ({ name: p.name, role: p.role })),
  };
}

onMounted(async () => {
  try {
    terms.value = keyTerms(await getAgreement(props.agreementId));
  } catch (e) {
    termsError.value = agreementUnavailable(e)
      ? AGREEMENT_UNAVAILABLE_MESSAGE
      : (busyMessage(e) ?? "Could not load the saved terms. Please try again.");
  }
});

onMounted(async () => {
  try {
    const q = await getStampQuote(props.agreementId);
    quote.value = q;
    const preselected =
      q.options.find((o) => o.recommended) ?? q.options[0] ?? null;
    selectedValue.value = preselected?.stampValueMinorUnits ?? null;
  } catch (e) {
    loadError.value = agreementUnavailable(e)
      ? AGREEMENT_UNAVAILABLE_MESSAGE
      : (busyMessage(e) ??
        "Could not load the stamp duty for this agreement. Please try again.");
  } finally {
    loading.value = false;
  }
});

const selected = computed<StampQuoteOption | null>(
  () =>
    quote.value?.options.find(
      (o) => o.stampValueMinorUnits === selectedValue.value,
    ) ?? null,
);

const canPay = computed(
  () =>
    !!quote.value?.available &&
    !!terms.value &&
    !!selected.value &&
    (!selected.value.belowDuty || acknowledged.value) &&
    !props.busy,
);

function shownDate(iso: string): string {
  return formatIso(iso) || "—";
}

function months(n: number): string {
  return n === 1 ? "1 month" : `${n} months`;
}

function choose(option: StampQuoteOption): void {
  selectedValue.value = option.stampValueMinorUnits;
  acknowledged.value = false;
}

function money(minorUnits: number | null | undefined): string {
  return minorUnits == null
    ? ""
    : formatMinorUnits(minorUnits, quote.value?.currency ?? "INR");
}

// The breakdown is display-only formatting of the server's lines -- no amount is derived here except
// the closing total, which is the server's dutyMinorUnits. The server marks each line's `delta`
// (DutyLine replay semantics): deltas carry an explicit sign and are never mistaken for a total;
// every other line (QUANTITY, BASE, and the informational ESCALATION) is an unsigned amount.
const QUANTITY_LABELS: Record<string, string> = {
  TOTAL_RENT: "Total rent for the term",
  AVERAGE_ANNUAL_RENT: "Average annual rent",
  MONTHLY_RENT: "Monthly rent",
  REFUNDABLE_DEPOSIT: "Refundable deposit",
  NON_REFUNDABLE_DEPOSIT: "Non-refundable deposit",
  ADVANCE_RENT: "Advance rent",
  PREMIUM: "Premium",
  DEPOSIT_NOTIONAL_INTEREST: "Notional interest on the deposit",
};

const rupees = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});
const grouped = new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 });

function isDelta(line: StampQuoteLine): boolean {
  return line.delta;
}

function isEscalation(line: StampQuoteLine): boolean {
  return line.kind === "ESCALATION";
}

function lineLabel(line: StampQuoteLine): string {
  if (line.kind === "QUANTITY") {
    return QUANTITY_LABELS[line.label] ?? sentenceCase(line.label);
  }
  if (isEscalation(line)) {
    return `Includes ${line.label}`;
  }
  const label = line.label
    .replace(/\d{4,}(\.\d+)?/g, (n) => grouped.format(Number(n)))
    .replace(
      /\brounded (UP|DOWN|HALF_UP)\b/,
      (_, mode: string) => `rounded ${mode.replace("_", " ").toLowerCase()}`,
    );
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function lineAmount(line: StampQuoteLine): string {
  const value = Number(line.amount);
  if (!isDelta(line)) return rupees.format(value);
  return `${value < 0 ? "−" : "+"}${rupees.format(Math.abs(value))}`;
}

function sentenceCase(name: string): string {
  const words = name.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

// The medium id comes from the jurisdiction's stamp paper catalog, so this must name every medium
// any catalog declares -- an unlabelled medium falls through to "Stamp paper" and mislabels itself.
// Karnataka's e-stamp is the pre-selected option on every Karnataka quote, so getting it wrong would
// be the most visible label on the screen.
function mediumLabel(option: StampQuoteOption): string {
  if (option.medium === "challan") return "Paid by challan";
  if (option.medium === "e-stamp") return "e-Stamp certificate";
  if (option.belowDuty) return "Single stamp paper";
  return "Stamp paper";
}

function confirm(): void {
  if (!quote.value || !selected.value || !canPay.value) return;
  emit("confirm", selectionFor(quote.value, selected.value));
}
</script>

<template>
  <section
    class="mx-auto w-full max-w-2xl rounded-lg border border-slate-200 bg-white p-4 sm:p-6"
    data-testid="stamp-quote-step"
  >
    <h2 class="text-lg font-semibold text-slate-900">Stamp duty</h2>

    <section
      class="mt-3 rounded border border-slate-200 p-3 text-sm"
      data-testid="key-terms"
    >
      <h3 class="font-medium text-slate-900">
        You are paying to stamp and sign this
      </h3>
      <p
        v-if="termsError"
        class="mt-2 text-red-600"
        role="alert"
        data-testid="key-terms-error"
      >
        {{ termsError }}
      </p>
      <template v-else-if="terms">
        <dl class="mt-2 space-y-2">
          <div>
            <dt class="text-slate-500">Property</dt>
            <dd
              class="whitespace-pre-line text-slate-900"
              data-testid="key-terms-address"
            >
              {{ terms.propertyAddress }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">Monthly rent</dt>
            <dd class="text-slate-900" data-testid="key-terms-rent">
              {{ formatRupees(terms.monthlyRent) }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">Security deposit</dt>
            <dd class="text-slate-900" data-testid="key-terms-deposit">
              {{ formatRupees(terms.securityDeposit) }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">Term</dt>
            <dd class="text-slate-900" data-testid="key-terms-term">
              {{ shownDate(terms.startDate) }} to
              {{ shownDate(terms.endDate) }} ·
              {{ months(terms.durationMonths) }}
            </dd>
          </div>
          <div>
            <dt class="text-slate-500">Parties</dt>
            <dd class="text-slate-900">
              <ul data-testid="key-terms-parties">
                <li v-for="(party, i) in terms.parties" :key="i">
                  {{ party.name }} ({{ roleLabel(party.role) }})
                </li>
              </ul>
            </dd>
          </div>
        </dl>
        <p class="mt-3 text-slate-700" data-testid="key-terms-go-back">
          If anything here is wrong, don't pay. Go back.
        </p>
      </template>
      <p v-else class="mt-2 text-slate-600" data-testid="key-terms-loading">
        Loading the saved terms…
      </p>
    </section>

    <p v-if="loading" class="mt-3 text-sm text-slate-600">
      Calculating stamp duty…
    </p>

    <p
      v-else-if="loadError && loadError !== termsError"
      class="mt-3 text-sm text-red-600"
      role="alert"
    >
      {{ loadError }}
    </p>

    <div v-else-if="quote && !quote.available" class="mt-3 space-y-3">
      <!-- UNPLANNABLE is a supported state whose duty no stamp paper covers, not a jurisdiction
           refusal; every other unavailable status is the jurisdiction gate stated ahead of time. -->
      <p class="text-sm text-slate-700" data-testid="stamp-quote-unavailable">
        {{
          quote.status === "UNPLANNABLE"
            ? STAMP_UNPLANNABLE_MESSAGE
            : JURISDICTION_UNSUPPORTED_MESSAGE
        }}
      </p>
    </div>

    <div v-else-if="quote" class="mt-3 space-y-4">
      <div class="flex flex-wrap items-baseline justify-between gap-2">
        <p class="text-sm text-slate-700">
          Stamp duty payable on this agreement
        </p>
        <p
          class="text-xl font-semibold text-slate-900"
          data-testid="stamp-duty"
        >
          {{ money(quote.dutyMinorUnits) }}
        </p>
      </div>

      <details class="rounded border border-slate-200 px-3 py-2 text-sm">
        <summary class="cursor-pointer text-slate-700">
          How this was calculated
        </summary>
        <ul class="mt-2 space-y-1 text-slate-600">
          <li
            v-for="(line, i) in quote.breakdown"
            :key="i"
            class="flex flex-wrap justify-between gap-2"
            :class="{ 'pl-3 text-slate-500': isEscalation(line) }"
            :data-testid="isEscalation(line) ? 'breakdown-escalation' : undefined"
          >
            <span>{{ lineLabel(line) }}</span>
            <span class="tabular-nums" data-testid="breakdown-amount">{{
              lineAmount(line)
            }}</span>
          </li>
          <li
            v-if="quote.dutyMinorUnits != null"
            class="flex flex-wrap justify-between gap-2 border-t border-slate-200 pt-1 font-medium text-slate-900"
            data-testid="breakdown-total"
          >
            <span>Stamp duty</span>
            <span class="tabular-nums">{{ money(quote.dutyMinorUnits) }}</span>
          </li>
        </ul>
        <p v-if="quote.rule" class="mt-2 text-xs text-slate-500">
          {{ quote.rule.legalReference ?? quote.rule.id }}
          <span v-if="!quote.rule.reviewed">
            · figures not yet legally reviewed</span
          >
        </p>
      </details>

      <p
        v-if="quote.registrationRequired"
        class="rounded bg-amber-50 px-3 py-2 text-xs text-amber-900"
        data-testid="registration-notice"
      >
        This agreement may need to be registered with the Sub-Registrar.
        Registration is separate from stamp duty and is not included in this
        payment.
      </p>

      <fieldset
        v-if="!termsError"
        class="space-y-2"
        :disabled="quote.frozen"
      >
        <legend class="text-sm font-medium text-slate-900">
          {{ quote.frozen ? "Your stamp choice" : "Choose the stamp value" }}
        </legend>
        <label
          v-for="option in quote.options"
          :key="option.stampValueMinorUnits"
          class="flex cursor-pointer flex-wrap items-center justify-between gap-2 rounded border px-3 py-2"
          :class="
            option.stampValueMinorUnits === selectedValue
              ? 'border-slate-900'
              : 'border-slate-200'
          "
          :data-testid="`stamp-option-${option.stampValueMinorUnits}`"
        >
          <span class="flex items-center gap-2">
            <input
              type="radio"
              name="stamp-option"
              :checked="option.stampValueMinorUnits === selectedValue"
              @change="choose(option)"
            />
            <span class="text-sm text-slate-900">
              {{ money(option.stampValueMinorUnits) }}
              <span class="text-slate-500">· {{ mediumLabel(option) }}</span>
              <span
                v-if="option.recommended && !option.belowDuty"
                class="ml-1 rounded bg-emerald-50 px-1.5 text-xs text-emerald-800"
                >Recommended</span
              >
              <span
                v-if="option.belowDuty"
                class="ml-1 rounded bg-red-50 px-1.5 text-xs text-red-800"
                >Below stamp duty</span
              >
            </span>
          </span>
          <span class="text-sm text-slate-700"
            >You pay {{ money(option.totalMinorUnits) }}</span
          >
        </label>
      </fieldset>

      <div
        v-if="selected?.belowDuty && !quote.frozen && !termsError"
        class="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-900"
        data-testid="under-stamp-warning"
      >
        <!-- ToS §7 (termsOfService.ts, our-fee) paraphrases this warning: re-check it when this text changes. -->
        <p>
          This stamp value is below the stamp duty payable on this agreement. An
          under-stamped agreement cannot be relied on as evidence in court until
          the missing duty and a penalty of up to ten times that amount are
          paid.
        </p>
        <label class="mt-2 flex items-start gap-2">
          <input
            v-model="acknowledged"
            type="checkbox"
            data-testid="under-stamp-acknowledge"
          />
          <span>I understand and want to use this stamp value anyway.</span>
        </label>
      </div>
    </div>

    <p v-if="error" class="mt-3 text-sm text-red-600" role="alert">
      {{ error }}
    </p>

    <div class="mt-5 flex flex-wrap justify-end gap-2">
      <button
        type="button"
        class="rounded border border-slate-300 px-4 py-2 text-sm text-slate-700"
        @click="emit('cancel')"
      >
        Back
      </button>
      <button
        v-if="quote?.available && !termsError"
        type="button"
        class="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
        :disabled="!canPay"
        data-testid="stamp-quote-pay"
        @click="confirm"
      >
        {{
          busy
            ? "Opening checkout…"
            : `Continue to payment${selected ? ` · ${money(selected.totalMinorUnits)}` : ""}`
        }}
      </button>
    </div>
  </section>
</template>
