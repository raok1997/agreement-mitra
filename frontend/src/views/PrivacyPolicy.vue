<script setup lang="ts">
// The privacy policy at /privacy.
//
// IT IS RENDERED FROM DATA, NOT WRITTEN HERE. The text lives in src/content/privacyPolicy.ts, which
// is also the source of the counsel-facing docs/PRIVACY-POLICY.md -- one text, two faces. Like the
// terms it must not look finished: what counsel owes renders as a visible, labelled gap.

import DraftBanner from "../components/DraftBanner.vue";
import LegalClause from "../components/LegalClause.vue";
import OperatorDetails from "../components/OperatorDetails.vue";
import SiteFooter from "../components/SiteFooter.vue";
import {
  OPERATING_ENTITY,
  type OperatingEntity,
} from "../content/operatingEntity";
import { PRIVACY_POLICY } from "../content/privacyPolicy";

withDefaults(defineProps<{ entity?: OperatingEntity }>(), {
  entity: () => OPERATING_ENTITY,
});

const emit = defineEmits<{ (e: "back"): void }>();

const doc = PRIVACY_POLICY;
</script>

<template>
  <main
    class="mx-auto max-w-3xl px-4 py-10"
    aria-labelledby="privacy-heading"
    data-testid="privacy-policy"
  >
    <h1 id="privacy-heading" class="text-2xl font-semibold text-slate-900">
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
      data-testid="legal-back"
      @click="emit('back')"
    >
      Back
    </button>
  </main>
  <SiteFooter :entity="entity" />
</template>
