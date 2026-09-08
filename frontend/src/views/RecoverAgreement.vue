<script setup lang="ts">
// "I paid for an agreement and I have lost the link."
//
// THE RESPONSE IS ALWAYS THE SAME, AND THAT IS THE POINT. The server answers 202 with an empty body
// whether the reference is unknown, unpaid, already claimed, has nobody contactable, or was sent
// successfully - so this screen shows one outcome for all of them. A tracking reference carries far
// less entropy than an agreement id; if this told you whether a reference existed, every paid
// agreement would be enumerable and full party PII would leak. Because it does not, guessing gains
// an attacker nothing: any email produced goes to the agreement's own parties, never to whoever
// asked.
//
// So: do not add a "no agreement found" branch here, however much better the UX would feel.
//
// The one thing we CAN check locally is the reference's own check character. That catches typos
// without asking the server anything, so a mistyped reference is corrected here rather than
// silently mailing nobody.

import { computed, ref } from "vue";
import { requestRecovery } from "../api/recovery";
import {
  isWellFormedReference,
  normalizeReference,
} from "../api/trackingReference";

const emit = defineEmits<{ (e: "back"): void }>();

const reference = ref("");
const submitting = ref(false);
const submitted = ref(false);
const error = ref<string | null>(null);

const normalized = computed(() => normalizeReference(reference.value));
const looksValid = computed(() => isWellFormedReference(normalized.value));
const showFormatHint = computed(
  () => normalized.value.length > 0 && !looksValid.value,
);

async function submit(): Promise<void> {
  if (!looksValid.value || submitting.value) return;
  submitting.value = true;
  error.value = null;
  try {
    await requestRecovery(normalized.value);
    submitted.value = true;
  } catch {
    // A transport failure is the ONLY thing that may produce a different outcome here, and even
    // then it says nothing about the reference - only that we could not ask.
    error.value = "We could not send that request. Please try again.";
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <section class="mx-auto max-w-lg p-6" aria-labelledby="recover-heading">
    <h2 id="recover-heading" class="text-xl font-semibold text-slate-900">
      Find your agreement
    </h2>

    <template v-if="!submitted">
      <p class="mt-2 text-sm text-slate-600">
        Enter the reference from your agreement and we will email the link to
        the parties on it.
      </p>

      <form class="mt-6" @submit.prevent="submit">
        <label class="block">
          <span class="text-sm font-medium text-slate-700"
            >Agreement reference</span
          >
          <input
            v-model="reference"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder="AM3G3VXSAKD"
            :aria-invalid="showFormatHint"
            aria-describedby="recover-format-hint"
            class="mt-1 w-full rounded border border-slate-300 px-3 py-2 font-mono text-sm uppercase"
            data-testid="recovery-reference"
          />
        </label>
        <p
          id="recover-format-hint"
          class="mt-1 text-xs"
          :class="showFormatHint ? 'text-amber-800' : 'text-slate-500'"
        >
          <template v-if="showFormatHint">
            That reference does not look right. Check it against your agreement.
          </template>
          <template v-else>
            It starts with AM and is eleven characters long.
          </template>
        </p>

        <p v-if="error" class="mt-3 text-sm text-red-700" role="alert">
          {{ error }}
        </p>

        <div class="mt-6 flex items-center gap-3">
          <button
            type="submit"
            class="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
            :disabled="!looksValid || submitting"
            data-testid="recovery-submit"
          >
            {{ submitting ? "Sending..." : "Email me the link" }}
          </button>
          <button
            type="button"
            class="text-sm text-slate-600 underline"
            @click="emit('back')"
          >
            Back
          </button>
        </div>
      </form>
    </template>

    <!-- One outcome, whatever happened. See the note at the top of this file before changing it. -->
    <template v-else>
      <div class="mt-6 rounded-lg bg-slate-50 p-4" data-testid="recovery-sent">
        <p class="text-sm font-medium text-slate-900">Check your email</p>
        <p class="mt-1 text-sm text-slate-600">
          If that reference matches a paid agreement, we have emailed a link to
          the parties on it. The link opens the agreement and lets you carry on.
        </p>
        <p class="mt-2 text-sm text-slate-600">
          Nothing arrived? Check the reference is right, and that you are
          looking in the inbox of an address on the agreement.
        </p>
      </div>
      <button
        type="button"
        class="mt-6 text-sm text-slate-600 underline"
        @click="emit('back')"
      >
        Back
      </button>
    </template>
  </section>
</template>
