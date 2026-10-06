<script setup lang="ts">
// The contact page at /contact.
//
// IT COMPOSES FACTS THAT ALREADY HAVE A HOME and adds no copy of any of them: the support email and
// hours from promises.ts, the terms' contact clause object, and the operator details. It carries no
// last-updated date because it holds no policy text of its own.

import DraftBanner from "../components/DraftBanner.vue";
import LegalClause from "../components/LegalClause.vue";
import OperatorDetails from "../components/OperatorDetails.vue";
import SiteFooter from "../components/SiteFooter.vue";
import {
  OPERATING_ENTITY,
  type OperatingEntity,
} from "../content/operatingEntity";
import {
  CONTACT_EMAIL,
  CONTACT_PAGE_BANNER,
  SUPPORT_HOURS,
} from "../content/promises";
import { TERMS_CONTACT_CLAUSE } from "../content/termsOfService";

withDefaults(defineProps<{ entity?: OperatingEntity }>(), {
  entity: () => OPERATING_ENTITY,
});

const emit = defineEmits<{ (e: "back"): void }>();
</script>

<template>
  <main
    class="mx-auto max-w-3xl px-4 py-10"
    aria-labelledby="contact-heading"
    data-testid="contact-page"
  >
    <h1 id="contact-heading" class="text-2xl font-semibold text-slate-900">
      Contact us
    </h1>

    <DraftBanner :text="CONTACT_PAGE_BANNER" :lead="null" />

    <dl
      class="mt-6 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm text-slate-700"
    >
      <dt class="font-medium">Email</dt>
      <dd>
        <a
          class="underline"
          :href="`mailto:${CONTACT_EMAIL}`"
          data-testid="contact-email"
        >
          {{ CONTACT_EMAIL }}
        </a>
      </dd>
      <dt class="font-medium">Hours</dt>
      <dd data-testid="contact-hours">{{ SUPPORT_HOURS }}</dd>
    </dl>

    <LegalClause :clause="TERMS_CONTACT_CLAUSE" />

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
