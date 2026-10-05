<script setup lang="ts">
// The site footer, shared by the landing page and /terms. It discloses the legal entity operating
// the service (operating-entity-disclosure D5). It reads no env: the views pass the resolved
// entity, so the component renders the same whatever is in a developer's .env or shell. Values are
// build-time constants, so the footer makes no request, and they render as escaped text only.
import { CONTACT_EMAIL } from "../content/promises";
import type { OperatingEntity } from "../content/operatingEntity";

defineProps<{ entity: OperatingEntity }>();
</script>

<template>
  <footer class="border-t border-ink-200 bg-white">
    <div
      class="mx-auto flex max-w-6xl flex-col gap-4 px-4 py-6 text-sm text-ink-500 md:flex-row md:items-start md:justify-between"
    >
      <div class="space-y-1">
        <p>&copy; 2026 AgreementMitra. Online rental agreements for India.</p>
        <p data-testid="footer-operator">
          AgreementMitra is a service of {{ entity.legalName }}.
        </p>
        <p class="text-xs" data-testid="footer-llpin">
          LLPIN: {{ entity.llpin ?? "being issued" }}
        </p>
        <p
          v-if="entity.registeredOffice"
          class="text-xs"
          data-testid="footer-office"
        >
          Registered office: {{ entity.registeredOffice }}
        </p>
      </div>
      <div class="flex items-center gap-4">
        <a class="font-medium text-ink-600 hover:text-brand-700" href="/terms">
          Terms of service
        </a>
        <a
          class="font-medium text-ink-600 hover:text-brand-700"
          :href="`mailto:${CONTACT_EMAIL}`"
        >
          {{ CONTACT_EMAIL }}
        </a>
      </div>
    </div>
  </footer>
</template>
