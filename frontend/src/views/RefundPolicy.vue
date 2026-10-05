<script setup lang="ts">
// The refunds and cancellation policy at /refunds.
//
// IT HOLDS NO TEXT OF ITS OWN. It renders the terms of service's refunds clause object itself --
// same paragraphs, same gap note, same status -- so the refund policy cannot drift from the terms.
// The intro names no clause number, so renumbering the terms cannot make it wrong.

import DraftBanner from "../components/DraftBanner.vue";
import LegalClause from "../components/LegalClause.vue";
import SiteFooter from "../components/SiteFooter.vue";
import {
  OPERATING_ENTITY,
  type OperatingEntity,
} from "../content/operatingEntity";
import {
  TERMS_OF_SERVICE,
  TERMS_REFUNDS_CLAUSE,
} from "../content/termsOfService";

withDefaults(defineProps<{ entity?: OperatingEntity }>(), {
  entity: () => OPERATING_ENTITY,
});

const emit = defineEmits<{ (e: "back"): void }>();
</script>

<template>
  <main
    class="mx-auto max-w-3xl px-4 py-10"
    aria-labelledby="refunds-heading"
    data-testid="refund-policy"
  >
    <h1 id="refunds-heading" class="text-2xl font-semibold text-slate-900">
      Refunds and cancellation
    </h1>
    <p class="mt-1 text-sm text-slate-500">
      Last updated {{ TERMS_OF_SERVICE.lastUpdated }}
    </p>

    <DraftBanner :text="TERMS_OF_SERVICE.banner" />

    <p class="mt-6 text-sm text-slate-700" data-testid="refund-intro">
      This is the refunds and cancellation section of our
      <a class="underline" href="/terms">terms of service</a>.
    </p>

    <LegalClause :clause="TERMS_REFUNDS_CLAUSE" />

    <button
      type="button"
      class="mt-10 text-sm text-slate-600 underline"
      data-testid="legal-back"
      @click="emit('back')"
    >
      Back
    </button>
  </main>
  <SiteFooter :entity="entity" />
</template>
