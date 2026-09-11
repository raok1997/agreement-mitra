<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { AgreementView, Role } from "../api/client";
import { auth } from "../api/authStore";
import {
  getPaymentProgress,
  payForAgreement,
  PaymentHttpError,
  type PaymentProgress,
} from "../api/payments";
import {
  downloadSignedDocument,
  getSigningProgress,
  SigningProgressHttpError,
  type FulfilmentStage,
  type PartyProgress,
  type SigningProgress,
} from "../api/signingProgress";
import { usePolling, type PollVerdict } from "../composables/usePolling";
import { LINK_UNAVAILABLE_MESSAGE } from "./linkCopy";

// The landing behind the emailed link. It answers "where has my agreement got to?" from three
// server reads and nothing else: the agreement (already loaded by the shell, which owns the
// claimed-or-unknown branch), payment state, and signing progress. No milestone is ever advanced
// on the strength of something that happened in this tab, and there is no edit here -- the terms
// froze when the order was placed, and this page says where things stand rather than pretending
// otherwise.

const props = defineProps<{
  agreement: AgreementView;
  /** Injectable so tests never wait on a real clock. */
  pollIntervalMs?: number;
  pollWait?: (ms: number) => Promise<void>;
}>();

const emit = defineEmits<{
  /** The agreement was claimed into an account while this page was open. */
  (e: "sign-in"): void;
}>();

const payment = ref<PaymentProgress | null>(null);
const progress = ref<SigningProgress | null>(null);
const loadError = ref<string | null>(null);
const unavailable = ref(false);
const signedIn = computed(() => !!auth.session);

// --- milestone rules (design D2). Sets, never enum order. -------------------------------------

const PAID_STATES = new Set(["PAID", "WAIVED"]);
const STAMP_DONE = new Set<FulfilmentStage>([
  "STAMPED",
  "OUT_FOR_SIGNATURE",
  "SIGNED",
  "FAILED",
  "EXPIRED",
]);
const SIGNING_HALTED = new Set<FulfilmentStage>(["FAILED", "EXPIRED"]);
const TERMINAL = new Set<FulfilmentStage>([
  "SIGNED",
  "EXPIRED",
  "FAILED",
  "STAMP_FAILED",
]);

type Condition = "done" | "current" | "failed" | "pending";

interface Milestone {
  key: string;
  label: string;
  condition: Condition;
  /** A plain-words line for failed/halted states, so the outcome is stated, not hidden. */
  note?: string;
}

const paid = computed(() => PAID_STATES.has(payment.value?.paymentState ?? ""));
const stage = computed<FulfilmentStage>(
  () => progress.value?.stage ?? "NOT_STARTED",
);
const ended = computed(() => TERMINAL.has(stage.value));

function roleLabel(role: Role | string | null | undefined): string {
  return role === "OWNER" ? "Owner" : role === "TENANT" ? "Tenant" : "Party";
}

function partyName(p: PartyProgress): string {
  const signer = props.agreement.signers.find((s) => s.id === p.signerId);
  const role = roleLabel(p.role);
  return signer ? `${signer.name} (${role})` : role;
}

const partyMilestones = computed<Milestone[]>(() => {
  const parties = progress.value?.parties ?? [];
  const nextToSign = parties.find((p) => p.status === "PENDING");
  return parties.map((p) => {
    const name = partyName(p);
    const [condition, note]: [Condition, string?] =
      p.status === "SIGNED"
        ? ["done"]
        : p.status === "REJECTED"
          ? ["failed", `${name} declined to sign.`]
          : p.status === "EXPIRED"
            ? ["failed", `${name}'s signing window expired.`]
            : SIGNING_HALTED.has(stage.value)
              ? ["pending", "Signing halted."]
              : [
                  stage.value === "OUT_FOR_SIGNATURE" && p === nextToSign
                    ? "current"
                    : "pending",
                ];
    return { key: p.signerId, label: `${name} signs`, condition, note };
  });
});

const milestones = computed<Milestone[]>(() => {
  const s = stage.value;
  const [stampCondition, stampNote]: [Condition, string?] = STAMP_DONE.has(s)
    ? ["done"]
    : s === "STAMP_FAILED"
      ? [
          "failed",
          "The e-stamp could not be applied. Our team will be in touch.",
        ]
      : [paid.value && s === "AWAITING_STAMP" ? "current" : "pending"];
  return [
    { key: "drafted", label: "Drafted", condition: "done" },
    {
      key: "paid",
      label: "Paid",
      // Unpaid on a live agreement is the customer's next step; unpaid on one that has ended is
      // just a fact -- never invite money for a dead order.
      condition: paid.value ? "done" : ended.value ? "pending" : "current",
    },
    {
      key: "stamp",
      label: "E-stamped",
      condition: stampCondition,
      note: stampNote,
    },
    ...partyMilestones.value,
    {
      key: "completed",
      label: "Completed",
      condition: s === "SIGNED" ? "done" : "pending",
    },
  ];
});

// --- reads and polling (design D3) -------------------------------------------------------------

// Two readers (the poll loop and a payment attempt) can be in flight at once; only the newest
// read may write, or a slow older response would roll a fresh PAID back to UNPAID.
let readSequence = 0;
// The verdict of the newest read that actually landed, so a caller racing the poll loop acts on
// the latest server state rather than on its own possibly-stale read.
let latestVerdict: PollVerdict = "continue";

function isUnavailable(reason: unknown): boolean {
  return (
    (reason instanceof SigningProgressHttpError ||
      reason instanceof PaymentHttpError) &&
    reason.status === 404
  );
}

async function readOnce(): Promise<PollVerdict> {
  const verdict = await readLatest();
  if (verdict !== null) latestVerdict = verdict;
  return verdict ?? "continue";
}

/** One read; null when a newer read overtook it and its result must not be applied. */
async function readLatest(): Promise<PollVerdict | null> {
  const seq = ++readSequence;
  // Payment cannot un-settle, so once it is paid that read is pure waste for the hours the tab
  // may sit on the stamp and signing phases.
  const [pay, prog] = await Promise.allSettled([
    paid.value
      ? Promise.resolve(payment.value as PaymentProgress)
      : getPaymentProgress(props.agreement.id),
    getSigningProgress(props.agreement.id),
  ]);
  if (seq !== readSequence) return null;
  if (pay.status === "fulfilled") payment.value = pay.value;
  if (prog.status === "fulfilled") progress.value = prog.value;
  // Either read answering 404 means the agreement is no longer ours to see -- claimed into an
  // account while we were looking. Whichever settled first must not decide the outcome.
  if (
    (pay.status === "rejected" && isUnavailable(pay.reason)) ||
    (prog.status === "rejected" && isUnavailable(prog.reason))
  ) {
    unavailable.value = true;
    return "stop";
  }
  if (pay.status === "rejected") throw pay.reason;
  if (prog.status === "rejected") throw prog.reason;
  if (prog.value.terminal) {
    return prog.value.stage === "SIGNED" && !prog.value.signedDocumentReady
      ? "slow"
      : "stop";
  }
  return "continue";
}

const polling = usePolling(readOnce, {
  intervalMs: props.pollIntervalMs,
  wait: props.pollWait,
});

function somethingCanHappen(): boolean {
  // Anything with an order, a payment, or a started pipeline is worth watching -- including
  // "finalised but unpaid", which is where the payment route leaves a customer. A draft with none
  // of those has nothing to wait for.
  const p = payment.value;
  return (
    p?.orderStatus != null ||
    p?.paymentState !== "UNPAID" ||
    stage.value !== "NOT_STARTED"
  );
}

async function load(): Promise<void> {
  await readOnce();
  if (latestVerdict === "stop") polling.stop();
  else if (somethingCanHappen()) polling.start();
}

onMounted(async () => {
  loadError.value = null;
  try {
    await load();
  } catch {
    loadError.value =
      "Could not load the agreement's status. Please try again.";
  }
});

// --- actions ------------------------------------------------------------------------------------

const paying = ref(false);
const payError = ref<string | null>(null);
async function pay(): Promise<void> {
  paying.value = true;
  payError.value = null;
  try {
    await payForAgreement(props.agreement.id, {
      name: "AgreementMitra",
      description: `Agreement ${props.agreement.trackingNumber}`,
    });
  } catch {
    payError.value =
      "Payment cannot be started yet. Contact support quoting your reference.";
  } finally {
    paying.value = false;
  }
  // Whatever the checkout said, the server decides -- re-read and keep watching.
  try {
    await load();
  } catch {
    // The poll will retry.
  }
}

const downloading = ref(false);
const downloadError = ref<string | null>(null);
async function download(): Promise<void> {
  downloading.value = true;
  downloadError.value = null;
  try {
    await downloadSignedDocument(
      props.agreement.id,
      `${props.agreement.trackingNumber}-signed.pdf`,
    );
  } catch {
    downloadError.value = "Could not download the document. Please try again.";
  } finally {
    downloading.value = false;
  }
}

// --- display helpers ----------------------------------------------------------------------------

const rupees = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 0,
});
/** A date-only ISO string is a calendar date, not an instant: parse it as local, never as UTC. */
function date(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });
}

const CONDITION_CLASS: Record<Condition, string> = {
  done: "border-green-500 bg-green-500 text-white",
  current: "border-amber-500 bg-amber-50 text-amber-700",
  failed: "border-red-500 bg-red-50 text-red-700",
  pending: "border-slate-300 bg-white text-slate-400",
};
const CONDITION_MARK: Record<Condition, string> = {
  done: "✓",
  current: "●",
  failed: "✕",
  pending: "○",
};
</script>

<template>
  <section class="flex flex-col gap-5" data-testid="agreement-status">
    <header>
      <p class="text-xs uppercase tracking-wide text-slate-500">Agreement</p>
      <h1
        class="text-xl font-semibold text-slate-900"
        data-testid="status-reference"
      >
        {{ agreement.trackingNumber }}
      </h1>
      <p class="mt-1 text-sm text-slate-600">
        Quote this reference if you contact us.
      </p>
    </header>

    <dl
      class="grid grid-cols-1 gap-x-6 gap-y-2 text-sm sm:grid-cols-2"
      data-testid="status-terms"
    >
      <div class="sm:col-span-2">
        <dt class="text-slate-500">Property</dt>
        <dd class="text-slate-900">{{ agreement.propertyAddress }}</dd>
      </div>
      <div>
        <dt class="text-slate-500">Monthly rent</dt>
        <dd class="text-slate-900">
          {{ rupees.format(agreement.monthlyRent) }}
        </dd>
      </div>
      <div>
        <dt class="text-slate-500">Term</dt>
        <dd class="text-slate-900">
          {{ date(agreement.startDate) }} – {{ date(agreement.endDate) }}
        </dd>
      </div>
      <div class="sm:col-span-2">
        <dt class="text-slate-500">Parties</dt>
        <dd class="text-slate-900">
          <span
            v-for="s in agreement.signers"
            :key="s.id"
            class="mr-3"
            :data-testid="`status-party-${s.id}`"
          >
            {{ s.name }}
            <span class="text-slate-500">({{ roleLabel(s.role) }})</span>
          </span>
        </dd>
      </div>
    </dl>

    <p v-if="loadError" class="text-sm text-red-600" data-testid="status-error">
      {{ loadError }}
    </p>

    <div
      v-else-if="unavailable"
      class="rounded border border-slate-200 bg-slate-50 p-4"
      data-testid="status-claimed"
    >
      <p class="text-sm text-slate-700">{{ LINK_UNAVAILABLE_MESSAGE }}</p>
      <button
        v-if="!signedIn"
        type="button"
        class="mt-3 rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white"
        @click="emit('sign-in')"
      >
        Sign in
      </button>
    </div>

    <ol
      v-else-if="progress"
      class="flex flex-col gap-3"
      data-testid="status-timeline"
    >
      <li
        v-for="m in milestones"
        :key="m.key"
        class="flex items-start gap-3"
        :data-testid="`milestone-${m.key}`"
        :data-condition="m.condition"
      >
        <span
          class="mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-xs"
          :class="CONDITION_CLASS[m.condition]"
          aria-hidden="true"
        >
          {{ CONDITION_MARK[m.condition] }}
        </span>
        <div>
          <p
            class="text-sm"
            :class="
              m.condition === 'pending'
                ? 'text-slate-400'
                : 'font-medium text-slate-900'
            "
          >
            {{ m.label }}
          </p>
          <p v-if="m.note" class="text-xs text-slate-600">{{ m.note }}</p>
          <template v-if="m.key === 'paid' && m.condition === 'current'">
            <button
              type="button"
              class="mt-2 rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              :disabled="paying"
              data-testid="status-pay"
              @click="pay"
            >
              {{ paying ? "Opening checkout…" : "Complete payment" }}
            </button>
            <p v-if="payError" class="mt-1 text-xs text-red-600">
              {{ payError }}
            </p>
          </template>
          <template v-if="m.key === 'completed' && m.condition === 'done'">
            <button
              v-if="progress.signedDocumentReady"
              type="button"
              class="mt-2 rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
              :disabled="downloading"
              data-testid="status-download"
              @click="download"
            >
              {{ downloading ? "Preparing…" : "Download signed agreement" }}
            </button>
            <p
              v-else
              class="mt-1 text-xs text-slate-600"
              data-testid="status-document-preparing"
            >
              Your signed document is being prepared.
            </p>
            <p v-if="downloadError" class="mt-1 text-xs text-red-600">
              {{ downloadError }}
            </p>
          </template>
        </div>
      </li>
    </ol>

    <p v-else class="text-sm text-slate-600">Loading status…</p>
  </section>
</template>
