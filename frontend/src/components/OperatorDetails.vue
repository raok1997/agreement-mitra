<script setup lang="ts">
// The operator's identifiers, rendered after the clauses and never inside them: the accepted policy
// text must not change when an LLPIN is issued (operating-entity-disclosure D6).
import type { OperatingEntity } from "../content/operatingEntity";
import { CONTACT_EMAIL } from "../content/promises";

defineProps<{ entity: OperatingEntity }>();
</script>

<template>
  <section
    class="mt-10 border-t border-slate-200 pt-6"
    data-testid="terms-operator-details"
  >
    <h2 class="text-base font-semibold text-slate-900">Operator details</h2>
    <dl
      class="mt-3 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm text-slate-700"
    >
      <dt class="font-medium">Legal name</dt>
      <dd>{{ entity.legalName }}</dd>
      <dt class="font-medium">LLPIN</dt>
      <dd>{{ entity.llpin ?? "being issued" }}</dd>
      <dt class="font-medium">Registered office</dt>
      <dd>{{ entity.registeredOffice ?? "to be confirmed" }}</dd>
      <dt class="font-medium">Support</dt>
      <dd>{{ CONTACT_EMAIL }}</dd>
    </dl>
  </section>
</template>
