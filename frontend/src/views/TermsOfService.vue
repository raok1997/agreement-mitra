<script setup lang="ts">
// The published terms of service at /terms.
//
// IT IS RENDERED FROM DATA, NOT WRITTEN HERE. The text lives in src/content/termsOfService.ts and
// is also the source of the counsel-facing docs/TERMS-OF-SERVICE.md -- one text, two faces. See the
// header comment there for why.
//
// IT MUST NOT LOOK FINISHED. This is a draft published during founding-team beta, and the gaps in
// it are the point: a clause we have not written renders as a visible, labelled hole rather than
// being quietly omitted. A reader who cannot tell which parts exist is worse off than one reading
// no terms at all.

import {
  TERMS_CLAUSES,
  TERMS_LAST_UPDATED,
  TERMS_STATUS_BANNER,
  type Clause,
} from "../content/termsOfService";

const emit = defineEmits<{ (e: "back"): void }>();

const clauses = TERMS_CLAUSES;

function gapLabel(clause: Clause): string {
  return clause.status === "counsel"
    ? "Gap - with our lawyers"
    : "Gap - not yet decided";
}
</script>

<template>
  <main
    class="mx-auto max-w-3xl px-4 py-10"
    aria-labelledby="terms-heading"
    data-testid="terms-of-service"
  >
    <h1 id="terms-heading" class="text-2xl font-semibold text-slate-900">
      Terms of Service
    </h1>
    <p class="mt-1 text-sm text-slate-500">
      Last updated {{ TERMS_LAST_UPDATED }}
    </p>

    <!-- The banner is not decoration: nothing on this page may read as settled. -->
    <p
      class="mt-6 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900"
      data-testid="terms-draft-banner"
    >
      <span class="font-semibold">Draft, pending legal review.</span>
      {{ TERMS_STATUS_BANNER }}
    </p>

    <section
      v-for="clause in clauses"
      :key="clause.heading"
      class="mt-8"
      :data-testid="`terms-clause-${clause.status}`"
    >
      <h2 class="text-base font-semibold text-slate-900">
        {{ clause.heading }}
      </h2>

      <p
        v-if="clause.status !== 'drafted' && clause.gap"
        class="mt-2 rounded border-l-4 border-slate-400 bg-slate-50 p-3 text-sm text-slate-700"
        data-testid="terms-gap"
      >
        <span class="font-semibold">{{ gapLabel(clause) }}.</span>
        {{ clause.gap }}
      </p>

      <p
        v-for="(paragraph, index) in clause.body"
        :key="index"
        class="mt-3 text-sm leading-relaxed text-slate-700"
      >
        {{ paragraph }}
      </p>
    </section>

    <button
      type="button"
      class="mt-10 text-sm text-slate-600 underline"
      data-testid="terms-back"
      @click="emit('back')"
    >
      Back
    </button>
  </main>
</template>
