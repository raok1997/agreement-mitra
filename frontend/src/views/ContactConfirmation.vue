<script lang="ts">
// A companion block purely so the shape can be a named export: `<script setup>` cannot export.
// The parent needs this type to build the list it passes in.

/** One party as this step edits them. `id` is the server-side signer id, not a form index. */
export interface PartyContact {
  id: string;
  name: string;
  role: string;
  email: string;
  mobile: string;
}
</script>

<script setup lang="ts">
// The step between finalising an agreement and paying for it: confirm how each party will be
// reached.
//
// WHY THIS EXISTS. Contact details are optional while drafting, deliberately - the self-serve flow
// must not put a data wall in front of a visitor who has not decided to buy. But an agreement whose
// parties are unreachable cannot be delivered to them, and a customer who pays anonymously and
// closes the tab cannot be sent the link back to their own agreement. So the details are required
// at the moment money is about to move, and this is where they are asked for.
//
// THIS SCREEN ENFORCES NOTHING. The server refuses to create an order for an unreachable agreement,
// and that refusal is the actual gate. This step exists so customers meet the requirement somewhere
// it makes sense rather than as a rejection at the pay button.
//
// A CHANNEL THAT IS OFF IS NOT A PROMISE. Mobile numbers are collected, but no SMS or WhatsApp
// provider exists. Nothing here may say or imply that a message will be sent on a channel that is
// not enabled - so mobile is described as being for signing notifications and future delivery, and
// email is the only route described as how the agreement arrives.

import { computed, ref, watch } from "vue";

const props = defineProps<{
  parties: PartyContact[];
  /** Set while the parent is saving, so the confirm button can show progress and stay disabled. */
  saving?: boolean;
  /** Surfaced from the parent when saving the corrected details failed. */
  error?: string | null;
}>();

const emit = defineEmits<{
  (e: "confirm", parties: PartyContact[]): void;
  (e: "cancel"): void;
}>();

// A local working copy: the customer may correct several parties before committing, and a
// half-edited list must never be written back to the agreement.
const draft = ref<PartyContact[]>(props.parties.map((p) => ({ ...p })));

watch(
  () => props.parties,
  (next) => {
    draft.value = next.map((p) => ({ ...p }));
  },
);

// Mirrors the server's rule (PartyReachability): deliberately permissive, because address syntax is
// already validated at capture and a second, subtly different opinion about what an address is
// would only produce disagreements between the two.
const EMAIL = /[^@\s]+@[^@\s]+\.[^@\s]+/;
const MOBILE = /^\+?[0-9]{6,15}$/;

function emailUsable(party: PartyContact): boolean {
  return EMAIL.test(party.email.trim());
}

function mobileUsable(party: PartyContact): boolean {
  const value = party.mobile.trim();
  return value === "" || MOBILE.test(value);
}

/** Email is the only enabled channel, so reachability is email today. */
function reachable(party: PartyContact): boolean {
  return emailUsable(party);
}

const unreachable = computed(() => draft.value.filter((p) => !reachable(p)));
const mobileProblems = computed(() => draft.value.filter((p) => !mobileUsable(p)));
const ready = computed(
  () => unreachable.value.length === 0 && mobileProblems.value.length === 0,
);

/** Everything already on file: the step is then a confirmation, not a form. */
const nothingMissing = computed(() =>
  props.parties.every((p) => EMAIL.test(p.email.trim())),
);

function roleLabel(role: string): string {
  if (!role) return "Party";
  return role.charAt(0).toUpperCase() + role.slice(1).toLowerCase();
}

function confirm(): void {
  if (!ready.value || props.saving) return;
  emit(
    "confirm",
    draft.value.map((p) => ({
      ...p,
      email: p.email.trim(),
      mobile: p.mobile.trim(),
    })),
  );
}
</script>

<template>
  <section class="mx-auto max-w-2xl p-6" aria-labelledby="contact-confirmation-heading">
    <h2 id="contact-confirmation-heading" class="text-xl font-semibold text-slate-900">
      {{ nothingMissing ? "Confirm where we send the agreement" : "How should we reach each party?" }}
    </h2>

    <p class="mt-2 text-sm text-slate-600">
      <template v-if="nothingMissing">
        We will email the agreement to each party at the address below. Check they are right
        before you pay.
      </template>
      <template v-else>
        Each party needs an email address before payment. That is how they receive the agreement,
        and how you get back to it later if you close this page.
      </template>
    </p>

    <ul class="mt-6 space-y-5">
      <li
        v-for="(party, index) in draft"
        :key="party.id"
        class="rounded-lg border border-slate-200 p-4"
        :class="reachable(party) ? '' : 'border-amber-300 bg-amber-50'"
      >
        <p class="text-sm font-medium text-slate-900">
          {{ party.name || roleLabel(party.role) }}
          <span class="ml-2 text-xs font-normal uppercase tracking-wide text-slate-500">
            {{ roleLabel(party.role) }}
          </span>
        </p>

        <div class="mt-3 grid gap-3 sm:grid-cols-2">
          <label class="block">
            <span class="text-xs font-medium text-slate-700">
              Email <span aria-hidden="true">*</span>
            </span>
            <input
              v-model="party.email"
              type="email"
              autocomplete="email"
              :aria-invalid="!reachable(party)"
              :aria-describedby="`contact-email-help-${index}`"
              class="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm"
            />
            <span :id="`contact-email-help-${index}`" class="mt-1 block text-xs text-slate-500">
              We email the agreement here.
            </span>
          </label>

          <label class="block">
            <span class="text-xs font-medium text-slate-700">Mobile (optional)</span>
            <input
              v-model="party.mobile"
              type="tel"
              autocomplete="tel"
              :aria-invalid="!mobileUsable(party)"
              :aria-describedby="`contact-mobile-help-${index}`"
              class="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm"
            />
            <!-- No SMS or WhatsApp provider exists. Do not imply a message will be sent. -->
            <span :id="`contact-mobile-help-${index}`" class="mt-1 block text-xs text-slate-500">
              Used for signing notifications, and for delivery once we support it.
            </span>
          </label>
        </div>

        <p v-if="!reachable(party)" class="mt-2 text-xs text-amber-800">
          Add a valid email address for this party to continue.
        </p>
        <p v-else-if="!mobileUsable(party)" class="mt-2 text-xs text-amber-800">
          That mobile number does not look right. Leave it blank if you do not have it.
        </p>
      </li>
    </ul>

    <p v-if="props.error" class="mt-4 text-sm text-red-700" role="alert">
      {{ props.error }}
    </p>

    <div class="mt-6 flex items-center gap-3">
      <button
        type="button"
        class="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        :disabled="!ready || props.saving"
        @click="confirm"
      >
        {{ props.saving ? "Saving..." : "Confirm and continue to payment" }}
      </button>
      <button
        type="button"
        class="text-sm text-slate-600 underline"
        :disabled="props.saving"
        @click="emit('cancel')"
      >
        Back
      </button>
    </div>
  </section>
</template>
