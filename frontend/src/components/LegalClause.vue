<script setup lang="ts">
// One clause of a policy document. A clause we have not written renders as a visible, labelled
// hole rather than being quietly omitted: a reader who cannot tell which parts exist is worse off
// than one reading no policy at all. Text renders escaped, never as HTML.
import type { Clause } from "../content/legalDocument";

const props = defineProps<{ clause: Clause }>();

function gapLabel(): string {
  return props.clause.status === "counsel"
    ? "Gap - with our lawyers"
    : "Gap - not yet decided";
}
</script>

<template>
  <section class="mt-8" :data-testid="`terms-clause-${clause.status}`">
    <h2 class="text-base font-semibold text-slate-900">
      {{ clause.heading }}
    </h2>

    <p
      v-if="clause.status !== 'drafted' && clause.gap"
      class="mt-2 rounded border-l-4 border-slate-400 bg-slate-50 p-3 text-sm text-slate-700"
      data-testid="terms-gap"
    >
      <span class="font-semibold">{{ gapLabel() }}.</span>
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
</template>
