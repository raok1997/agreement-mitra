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

import DraftBanner from "../components/DraftBanner.vue";
import LegalClause from "../components/LegalClause.vue";
import OperatorDetails from "../components/OperatorDetails.vue";
import SiteFooter from "../components/SiteFooter.vue";
import {
  OPERATING_ENTITY,
  type OperatingEntity,
} from "../content/operatingEntity";
import { TERMS_OF_SERVICE } from "../content/termsOfService";

withDefaults(defineProps<{ entity?: OperatingEntity }>(), {
  entity: () => OPERATING_ENTITY,
});

const emit = defineEmits<{ (e: "back"): void }>();

const doc = TERMS_OF_SERVICE;
</script>

<template>
  <main
    class="mx-auto max-w-3xl px-4 py-10"
    aria-labelledby="terms-heading"
    data-testid="terms-of-service"
  >
    <h1 id="terms-heading" class="text-2xl font-semibold text-slate-900">
      {{ doc.title }}
    </h1>
    <p class="mt-1 text-sm text-slate-500">
      Last updated {{ doc.lastUpdated }}
    </p>

    <DraftBanner :text="doc.banner" />

    <LegalClause
      v-for="clause in doc.clauses"
      :key="clause.id"
      :clause="clause"
    />

    <OperatorDetails :entity="entity" />

    <button
      type="button"
      class="mt-10 text-sm text-slate-600 underline"
      data-testid="terms-back"
      @click="emit('back')"
    >
      Back
    </button>
  </main>
  <SiteFooter :entity="entity" />
</template>
