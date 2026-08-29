<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  listStampQueue,
  uploadStampForEntry,
  StaffQueueHttpError,
  type StampQueueEntry,
  type StampQueueParty,
} from "../api/staffQueue";

// The staff fulfilment console: every order awaiting an e-stamp, longest-waiting first, with the
// upload form opening ON a row. The reference is carried by the row, never re-typed -- which removes
// the transcription step that the reference's check character otherwise has to catch. Only the
// certificate fields are entered by hand.
//
// THE ROW IS A PURCHASE ORDER, not just an identifier. Buying the e-stamp means filling the vendor's
// form with the first party, the second party, and the STATE -- so the row leads with the state
// (it decides which stamp paper to buy) and lists every party with their father's name. Everything
// here is read off the row; nothing sends an operator back to the database.
//
// This screen is STAFF-only. The guard here is presentational: it stops a customer being shown a
// door that 403s. Authorization itself is entirely server-side.

const entries = ref<StampQueueEntry[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

// Which row's upload form is open (agreement id), and the per-upload state.
const openEntryId = ref<string | null>(null);
const submitting = ref(false);
const submitError = ref<string | null>(null);
const lastResult = ref<string | null>(null);

const certificateNumber = ref("");
const issueDate = ref("");
const dutyAmount = ref("");
const jurisdiction = ref("");
const scan = ref<File | null>(null);
// Checked by default: attaching the stamp and sending for signature is the normal fulfilment
// step, and leaving an order stamped-but-unsent strands it with nothing in this console to
// move it on. An operator who is not ready unticks it -- the deliberate act is the pause, not
// the send.
const initiateSigning = ref(true);

/**
 * What the certificate fields are PRE-FILLED with when a row's form opens.
 *
 * These are starting points, not answers: the operator is transcribing a certificate they actually
 * bought, and the number and duty on that certificate are the ones that must be stored. Pre-filling
 * only removes the typing for the case where the value is already known from the row -- the state,
 * which the row already carries, and the issue date, which is today for a stamp bought today.
 *
 * The certificate number defaults to the tracking reference so the field is never empty and the
 * value always matches the accepted shape; OVERWRITE IT with the real SHCIL number once the
 * certificate is in hand. The number is the single-use token that evidences duty payment, and the
 * database enforces uniqueness on it -- leaving the placeholder in stores an instrument whose stamp
 * evidence does not name a real certificate.
 */
const DEFAULT_DUTY_AMOUNT = "100";

/**
 * Today, as the `yyyy-MM-dd` a native date input and the server's ISO binder both expect.
 *
 * Built from the LOCAL calendar fields, not `toISOString()`: that renders UTC, so for an operator in
 * IST every upload before 05:30 would default to yesterday.
 */
function todayIso(): string {
  const now = new Date();
  const month = `${now.getMonth() + 1}`.padStart(2, "0");
  const day = `${now.getDate()}`.padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * The certificate-number character set the server accepts: alphanumerics, spaces, hyphen and slash,
 * starting and ending on an alphanumeric. Deliberately the same shape as the backend's guard --
 * mirrored here only so a rejection is explained AT the field instead of arriving as a generic 400
 * after the scan has been uploaded. The server remains the authority.
 */
const CERTIFICATE_NUMBER_SHAPE = /^[A-Za-z0-9][A-Za-z0-9 /-]*[A-Za-z0-9]$/;

/**
 * Why the certificate number is not acceptable, or null when it is. Underscores are the common slip
 * (they are not in the accepted set), so the message names the allowed characters rather than just
 * saying no.
 */
const certificateNumberError = computed<string | null>(() => {
  const value = certificateNumber.value.trim();
  if (!value) return null; // empty is "not filled in yet", not "wrong"
  if (value.length < 6 || value.length > 64) {
    return "The certificate number must be 6 to 64 characters.";
  }
  if (!CERTIFICATE_NUMBER_SHAPE.test(value)) {
    return "The certificate number may use letters, digits, spaces, - and / only (no underscores).";
  }
  return null;
});

const canSubmit = computed(
  () =>
    !submitting.value &&
    !!scan.value &&
    certificateNumber.value.trim().length >= 6 &&
    !certificateNumberError.value &&
    !!issueDate.value &&
    !!dutyAmount.value &&
    jurisdiction.value.trim().length > 0,
);

/** Waiting time as a coarse human phrase -- an operator needs "how stale", not a duration to the second. */
function waitedFor(seconds: number): string {
  if (seconds < 3600) return `${Math.max(1, Math.floor(seconds / 60))}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  return `${Math.floor(seconds / 86400)}d`;
}

/**
 * The row's one-line context. Payment state is deliberately NOT repeated here -- it gets its own
 * badge, because a bare "UNPAID" buried in a subtitle is not a reason a button does not work.
 */
function subtitle(entry: StampQueueEntry): string {
  return [
    entry.propertyCity || "Unknown city",
    `from ${entry.agreementStartDate}`,
  ].join(" - ");
}

/**
 * The parties of one side, in the order the server sent them (owners already first).
 *
 * All of them, not just the first: an agreement may carry several owners or several tenants, and
 * showing one would hide a name the certificate needs.
 */
function partiesOf(entry: StampQueueEntry, role: string): StampQueueParty[] {
  return entry.parties.filter((party) => party.role === role);
}

/**
 * "Father: X" rather than the "S/o X" an Indian instrument would use. We store a father's name but
 * no gender and no relationship, and instruments use S/o, D/o or W/o depending on the party --
 * printing "S/o" for everyone would misdescribe some of them. The neutral label carries the same
 * information without asserting something we never captured.
 */
function fatherLabel(party: StampQueueParty): string {
  return `Father: ${party.fatherName}`;
}

/**
 * Whether this order is blocked on payment.
 *
 * Unpaid orders stay on the queue on purpose -- filtering them out would turn "waiting on payment"
 * into "vanished", which is the harder thing to diagnose. But with the gate enforced they cannot be
 * stamped, so the row has to SAY so: an operator who clicks Upload and gets a 409 has been sent to
 * debug a certificate problem that does not exist.
 *
 * Presentational only. The backend refuses the intake regardless of what this client believes --
 * `paymentState` is server-managed and the gate is evaluated before any side effect.
 */
function awaitingPayment(entry: StampQueueEntry): boolean {
  return entry.paymentState === "UNPAID";
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    entries.value = await listStampQueue();
  } catch (e) {
    // Never surface server internals; distinguish only "not allowed" from "went wrong".
    error.value =
      e instanceof StaffQueueHttpError && (e.status === 401 || e.status === 403)
        ? "This console is for AgreementMitra staff."
        : "Could not load the stamp queue. Please try again.";
  } finally {
    loading.value = false;
  }
}

/**
 * Open the form on a row, pre-filled from what the row already knows.
 *
 * Jurisdiction comes from the PINNED TEMPLATE's state, not the property address -- that is the field
 * that decides which state's stamp paper was bought, and it is the same value the row's badge shows.
 * When the template cannot be resolved the row shows no state, so there is nothing to pre-fill and
 * the field is left empty for the operator rather than guessed at.
 */
function openUpload(entry: StampQueueEntry): void {
  openEntryId.value = entry.agreementId;
  submitError.value = null;
  lastResult.value = null;
  certificateNumber.value = entry.trackingReference;
  issueDate.value = todayIso();
  dutyAmount.value = DEFAULT_DUTY_AMOUNT;
  jurisdiction.value = entry.templateState ?? "";
  scan.value = null;
  initiateSigning.value = true;
}

function onScanPicked(event: Event): void {
  const input = event.target as HTMLInputElement;
  scan.value = input.files && input.files.length ? input.files[0] : null;
}

async function submit(entry: StampQueueEntry): Promise<void> {
  if (!canSubmit.value || !scan.value) return;
  submitting.value = true;
  submitError.value = null;
  try {
    const result = await uploadStampForEntry(entry, scan.value, {
      certificateNumber: certificateNumber.value.trim(),
      issueDate: issueDate.value,
      dutyAmount: dutyAmount.value,
      jurisdiction: jurisdiction.value.trim(),
      initiateSigning: initiateSigning.value,
    });
    // Echo back only what the server returned -- the certificate number arrives already redacted.
    // The two outcomes are reported SEPARATELY: the stamp can land while signing does not, and
    // saying "stamped" alone would leave an operator thinking the parties had been invited.
    const stamped = `Stamped ${result.trackingReference} (certificate ${result.certificateNumberRedacted})`;
    if (!initiateSigning.value) {
      lastResult.value = stamped;
    } else if (result.signingInitiated) {
      lastResult.value = `${stamped}. Sent for signature -- the first party has been invited.`;
    } else {
      lastResult.value =
        `${stamped}. SIGNING DID NOT START (${result.signingNotStartedReason ?? "unknown"}).` +
        " The stamp is attached and nothing was lost; signing can be started again for this order.";
    }
    openEntryId.value = null;
    await load(); // the stamped order leaves the queue
  } catch (e) {
    // 409 is no longer one situation. Naming the wrong one sends an operator to re-check a
    // certificate that is perfectly fine.
    if (e instanceof StaffQueueHttpError && e.paymentRequired) {
      submitError.value =
        "Refused: this order has not been paid for. Payment must clear before an e-stamp is bought, or a staff member must waive it.";
    } else if (e instanceof StaffQueueHttpError && e.status === 409) {
      submitError.value =
        "Refused: this order already has a stamp, or that certificate has already been used.";
    } else if (e instanceof StaffQueueHttpError && e.status === 400) {
      // 400 is either the scan or a certificate field. The client already guards the certificate
      // number's shape, so pointing at the scan first is pointing at the likelier cause.
      submitError.value =
        "Rejected: the scan must be a readable JPEG or PNG (at least 200x200, under 8 MB), and every certificate field must be filled in.";
    } else {
      submitError.value =
        "The upload could not be completed. Please try again.";
    }
  } finally {
    submitting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="flex flex-col gap-3" data-testid="staff-console">
    <div class="flex items-center justify-between">
      <h2 class="text-base font-semibold text-slate-800">
        Orders awaiting an e-stamp
      </h2>
      <button
        type="button"
        class="rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
        data-testid="queue-refresh"
        @click="load"
      >
        Refresh
      </button>
    </div>

    <p
      v-if="lastResult"
      class="text-sm text-green-700"
      data-testid="queue-result"
    >
      {{ lastResult }}
    </p>

    <p
      v-if="loading"
      class="py-6 text-sm text-slate-500"
      data-testid="queue-loading"
    >
      Loading the stamp queue...
    </p>
    <p
      v-else-if="error"
      class="py-6 text-sm text-red-600"
      data-testid="queue-error"
    >
      {{ error }}
    </p>
    <p
      v-else-if="!entries.length"
      class="rounded border border-dashed border-slate-300 px-4 py-8 text-center text-sm text-slate-500"
      data-testid="queue-empty"
    >
      No orders are waiting for a stamp.
    </p>

    <ul v-else class="flex flex-col gap-2" data-testid="queue-list">
      <li
        v-for="entry in entries"
        :key="entry.agreementId"
        class="flex flex-col gap-3 rounded-md border border-slate-200 px-4 py-3"
        :data-testid="`queue-row-${entry.agreementId}`"
      >
        <div class="flex flex-wrap items-center gap-3">
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <p
                class="truncate font-mono text-sm font-medium text-slate-800"
                :data-testid="`queue-reference-${entry.agreementId}`"
              >
                {{ entry.trackingReference }}
              </p>
              <!-- The state selects which stamp paper to buy, so it gets a badge of its own rather
                   than being a word inside a subtitle. -->
              <span
                v-if="entry.templateState"
                class="rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-semibold text-indigo-700"
                :data-testid="`queue-state-${entry.agreementId}`"
              >
                {{ entry.templateState }}
              </span>
            </div>
            <p
              v-if="entry.templateName"
              class="truncate text-xs font-medium text-slate-600"
              :data-testid="`queue-template-${entry.agreementId}`"
            >
              {{ entry.templateName }}
            </p>
            <!-- The template was superseded or archived after this agreement pinned it. Say so:
                 an operator has to know the state is missing, not guess it from the address. -->
            <p
              v-else
              class="text-xs italic text-amber-700"
              :data-testid="`queue-template-missing-${entry.agreementId}`"
            >
              Template unavailable - check the agreement before buying
            </p>
            <p class="text-xs text-slate-500">{{ subtitle(entry) }}</p>
          </div>
          <span
            class="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-semibold text-amber-700"
            :data-testid="`queue-waiting-${entry.agreementId}`"
          >
            waiting {{ waitedFor(entry.waitingSeconds) }}
          </span>
          <!-- Why the button is dead. An unpaid order stays on the queue on purpose; saying so is
               what stops an operator debugging a certificate problem that does not exist. -->
          <span
            v-if="awaitingPayment(entry)"
            class="rounded-full bg-rose-50 px-2 py-0.5 text-xs font-semibold text-rose-700"
            :data-testid="`queue-payment-${entry.agreementId}`"
          >
            Awaiting payment
          </span>
          <span
            v-else-if="entry.paymentState"
            class="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-semibold text-emerald-700"
            :data-testid="`queue-payment-${entry.agreementId}`"
          >
            {{ entry.paymentState === "WAIVED" ? "Payment waived" : "Paid" }}
          </span>
          <button
            type="button"
            class="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40"
            :disabled="awaitingPayment(entry)"
            :title="
              awaitingPayment(entry)
                ? 'This order has not been paid for. Payment must clear, or a staff member must waive it, before an e-stamp is bought.'
                : undefined
            "
            :data-testid="`queue-upload-${entry.agreementId}`"
            @click="openUpload(entry)"
          >
            Upload stamp
          </button>
        </div>

        <!-- The names that go on the certificate. Rendered for every row, not only the open one:
             an operator scanning the queue is deciding what to buy, not just which row to open. -->
        <dl
          v-if="entry.parties.length"
          class="grid gap-3 border-t border-slate-100 pt-3 sm:grid-cols-2"
          :data-testid="`queue-parties-${entry.agreementId}`"
        >
          <div v-if="partiesOf(entry, 'OWNER').length">
            <dt
              class="text-xs font-semibold uppercase tracking-wide text-slate-500"
            >
              First party
            </dt>
            <dd
              v-for="party in partiesOf(entry, 'OWNER')"
              :key="`${party.name}-${party.fatherName}`"
              class="mt-1 text-sm text-slate-800"
            >
              {{ party.name }}
              <span class="block text-xs text-slate-500">{{
                fatherLabel(party)
              }}</span>
            </dd>
          </div>
          <div v-if="partiesOf(entry, 'TENANT').length">
            <dt
              class="text-xs font-semibold uppercase tracking-wide text-slate-500"
            >
              Second party
            </dt>
            <dd
              v-for="party in partiesOf(entry, 'TENANT')"
              :key="`${party.name}-${party.fatherName}`"
              class="mt-1 text-sm text-slate-800"
            >
              {{ party.name }}
              <span class="block text-xs text-slate-500">{{
                fatherLabel(party)
              }}</span>
            </dd>
          </div>
        </dl>

        <form
          v-if="openEntryId === entry.agreementId"
          class="flex flex-col gap-2 border-t border-slate-200 pt-3"
          :data-testid="`queue-form-${entry.agreementId}`"
          @submit.prevent="submit(entry)"
        >
          <!-- The agreement is identified by THIS row; there is no reference field to mistype. -->
          <p class="text-xs text-slate-500">
            Attaching to
            <span class="font-mono">{{ entry.trackingReference }}</span>
          </p>
          <div class="grid gap-2 sm:grid-cols-2">
            <label class="flex flex-col gap-1 text-xs text-slate-600">
              Certificate number
              <input
                v-model="certificateNumber"
                type="text"
                class="rounded border border-slate-300 px-2 py-1.5 text-sm"
                data-testid="field-certificate-number"
              />
              <!-- Explained AT the field: the same rejection arriving as a generic 400 after the
                   scan has been uploaded sends an operator to check the image instead. -->
              <span
                v-if="certificateNumberError"
                class="text-xs text-red-600"
                data-testid="field-certificate-number-error"
                >{{ certificateNumberError }}</span
              >
              <span v-else class="text-xs text-slate-400"
                >Replace with the number on the purchased certificate.</span
              >
            </label>
            <label class="flex flex-col gap-1 text-xs text-slate-600">
              Issue date
              <input
                v-model="issueDate"
                type="date"
                class="rounded border border-slate-300 px-2 py-1.5 text-sm"
                data-testid="field-issue-date"
              />
            </label>
            <label class="flex flex-col gap-1 text-xs text-slate-600">
              Duty amount
              <input
                v-model="dutyAmount"
                type="text"
                inputmode="decimal"
                class="rounded border border-slate-300 px-2 py-1.5 text-sm"
                data-testid="field-duty-amount"
              />
            </label>
            <label class="flex flex-col gap-1 text-xs text-slate-600">
              Jurisdiction
              <input
                v-model="jurisdiction"
                type="text"
                class="rounded border border-slate-300 px-2 py-1.5 text-sm"
                data-testid="field-jurisdiction"
              />
            </label>
          </div>
          <label class="flex flex-col gap-1 text-xs text-slate-600">
            Scanned certificate (JPEG or PNG)
            <input
              type="file"
              accept="image/jpeg,image/png"
              class="text-sm"
              data-testid="field-scan"
              @change="onScanPicked"
            />
          </label>
          <label class="flex items-start gap-2 text-xs text-slate-600">
            <input
              v-model="initiateSigning"
              type="checkbox"
              class="mt-0.5"
              data-testid="field-initiate-signing"
            />
            <span>
              Send for signature after attaching
              <span class="block text-slate-500">
                Starts the Aadhaar eSign workflow and invites the first party. Untick to attach the
                stamp only.
              </span>
            </span>
          </label>
          <p
            v-if="submitError"
            class="text-sm text-red-600"
            data-testid="queue-submit-error"
          >
            {{ submitError }}
          </p>
          <div class="flex gap-2">
            <button
              type="submit"
              :disabled="!canSubmit"
              class="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
              :data-testid="`queue-submit-${entry.agreementId}`"
            >
              Attach stamp
            </button>
            <button
              type="button"
              class="rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
              data-testid="queue-cancel"
              @click="openEntryId = null"
            >
              Cancel
            </button>
          </div>
        </form>
      </li>
    </ul>
  </section>
</template>
