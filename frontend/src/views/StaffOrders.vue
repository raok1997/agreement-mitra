<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { busyMessage } from "../api/http";
import {
  fetchStaffOrders,
  StaffOrdersHttpError,
  type StaffOrderEntry,
  type StaffOrders,
} from "../api/staffOrders";
import {
  agreementPaymentText,
  doubleChargeNote,
  formatOrderInstant,
  formatOrderMoney,
  nextStep,
  outcomeLabel,
  RUNBOOK_SECTION,
  statusLabel,
} from "./staffOrderFormat";

// The staff Orders tab: payment orders that hold a captured payment nobody recorded, then recent
// orders that were not paid. It is a READ. Nothing here acts on an order -- no button, no link --
// and every string from the response is rendered by text interpolation, never as markup: payment
// ids and currencies are gateway-supplied.
//
// It loads when mounted. The console mounts it only while its tab is open, so reopening the tab is
// the refresh.

const orders = ref<StaffOrders | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

const groups = computed(() =>
  orders.value
    ? [
        {
          key: "stuck",
          title: "Paid but stuck",
          about:
            "The gateway captured a payment for these orders and it was not recorded.",
          empty: "No order holds a payment that was not recorded.",
          group: orders.value.stuck,
        },
        {
          key: "not-paid",
          title: "Not paid",
          about: "Orders from the last 30 days that were not paid.",
          empty: "No unpaid order in the last 30 days.",
          group: orders.value.notPaid,
        },
      ]
    : [],
);

/** "Amount mismatch" names both amounts; every other outcome is its label. */
function stuckLabel(entry: StaffOrderEntry, outcome: string): string {
  if (outcome !== "AMOUNT_MISMATCH") return outcomeLabel(outcome);
  const paid = entry.attempts
    .filter(
      (attempt) =>
        attempt.outcome === outcome && attempt.providerPaymentId !== null,
    )
    .map((attempt) =>
      formatOrderMoney(attempt.amountMinorUnits, attempt.currency),
    );
  const order = formatOrderMoney(entry.amountMinorUnits, entry.currency);
  return `${outcomeLabel(outcome)}: order ${order}, paid ${[...new Set(paid)].join(", ")}`;
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    orders.value = await fetchStaffOrders();
  } catch (e) {
    orders.value = null;
    // Never surface server internals; distinguish only "not allowed" from "went wrong".
    error.value =
      busyMessage(e) ??
      (e instanceof StaffOrdersHttpError &&
      (e.status === 401 || e.status === 403)
        ? "This console is for AgreementMitra staff."
        : "The orders could not be loaded. Please try again.");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="flex flex-col gap-4" data-testid="staff-orders">
    <div class="flex flex-col gap-1 text-xs text-slate-600">
      <p data-testid="orders-runbook">
        This list is read-only. What to do with a stuck order is in the runbook,
        section "{{ RUNBOOK_SECTION }}" of docs/DEPLOYMENT.md.
      </p>
      <p data-testid="orders-not-listed">
        An agreement staff confirmed or waived by hand has no payment order and
        is not listed here.
      </p>
    </div>

    <p
      v-if="loading"
      class="py-6 text-sm text-slate-500"
      data-testid="orders-loading"
    >
      Loading the orders...
    </p>
    <p
      v-else-if="error"
      class="py-6 text-sm text-red-600"
      data-testid="orders-error"
    >
      {{ error }}
    </p>

    <template v-else>
      <section
        v-for="{ key, title, about, empty, group } in groups"
        :key="key"
        class="flex flex-col gap-2"
        :data-testid="`orders-${key}`"
      >
        <h2 class="text-base font-semibold text-slate-800">{{ title }}</h2>
        <p class="text-xs text-slate-500">{{ about }}</p>
        <p
          v-if="group.truncated"
          class="text-xs font-medium text-amber-700"
          :data-testid="`orders-${key}-truncated`"
        >
          Showing the first {{ group.entries.length }}. More orders were left
          out.
        </p>
        <p
          v-if="!group.entries.length"
          class="rounded border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-500"
          :data-testid="`orders-${key}-empty`"
        >
          {{ empty }}
        </p>
        <ul v-else class="flex flex-col gap-2">
          <li
            v-for="entry in group.entries"
            :key="entry.paymentOrderId"
            class="flex flex-col gap-2 rounded-md border border-slate-200 px-4 py-3"
            :data-testid="`order-${entry.paymentOrderId}`"
          >
            <div class="flex flex-wrap items-center gap-2">
              <p class="font-mono text-sm font-medium text-slate-800">
                {{ entry.trackingReference }}
              </p>
              <span
                v-for="outcome in entry.stuckOutcomes"
                :key="outcome"
                class="rounded-full bg-rose-50 px-2 py-0.5 text-xs font-semibold text-rose-700"
                data-testid="order-outcome"
              >
                {{ stuckLabel(entry, outcome) }}
              </span>
              <span
                class="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-700"
                data-testid="order-status"
              >
                {{ statusLabel(entry.status) }}
              </span>
              <span class="text-sm text-slate-800" data-testid="order-amount">
                {{ formatOrderMoney(entry.amountMinorUnits, entry.currency) }}
              </span>
            </div>
            <p class="text-xs text-slate-500">
              Created {{ formatOrderInstant(entry.createdAt) }} · order
              <span class="break-all font-mono">{{
                entry.paymentOrderId
              }}</span>
              ·
              <span data-testid="order-agreement-payment">{{
                agreementPaymentText(entry.agreementPayment)
              }}</span>
            </p>

            <!-- Its own line, because the next step names the holder only when the reuse is the
                 step shown: behind an amount mismatch it would otherwise appear nowhere. -->
            <p
              v-if="entry.heldBy.length"
              class="text-xs text-slate-600"
              data-testid="order-held-by"
            >
              Payment id already recorded on
              <span class="font-mono">{{ entry.heldBy.join(", ") }}</span>
            </p>
            <p
              v-if="nextStep(entry)"
              class="text-sm font-medium text-slate-800"
              data-testid="order-next-step"
            >
              Next: {{ nextStep(entry) }}
            </p>
            <p
              v-if="doubleChargeNote(entry)"
              class="text-sm font-medium text-amber-700"
              data-testid="order-double-charge"
            >
              {{ doubleChargeNote(entry) }}
            </p>

            <ul
              v-if="entry.attempts.length"
              class="flex flex-col gap-1 border-t border-slate-100 pt-2"
            >
              <li
                v-for="(attempt, index) in entry.attempts"
                :key="index"
                class="text-xs text-slate-600"
                data-testid="order-attempt"
              >
                <span class="font-semibold text-slate-700">{{
                  outcomeLabel(attempt.outcome)
                }}</span>
                · {{ attempt.source }} ·
                {{
                  formatOrderMoney(attempt.amountMinorUnits, attempt.currency)
                }}
                · seen {{ attempt.attemptCount }}
                {{ attempt.attemptCount === 1 ? "time" : "times" }} ·
                {{ formatOrderInstant(attempt.firstReceivedAt) }}
                <template
                  v-if="
                    formatOrderInstant(attempt.lastReceivedAt) !==
                    formatOrderInstant(attempt.firstReceivedAt)
                  "
                >
                  to {{ formatOrderInstant(attempt.lastReceivedAt) }}
                </template>
                <span v-if="attempt.providerPaymentId" class="block">
                  Payment id
                  <span
                    class="break-all font-mono"
                    data-testid="attempt-payment-id"
                    >{{ attempt.providerPaymentId }}</span
                  >
                </span>
                <span
                  v-if="attempt.refusedRule || attempt.sqlState"
                  class="block"
                >
                  Refused by {{ attempt.refusedRule ?? "the database" }}
                  <template v-if="attempt.sqlState">
                    (SQL state {{ attempt.sqlState }})
                  </template>
                </span>
              </li>
            </ul>
          </li>
        </ul>
      </section>
    </template>
  </section>
</template>
