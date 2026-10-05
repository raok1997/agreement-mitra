<script setup lang="ts">
// The banner at the head of every policy page. It is not decoration: nothing on these pages may
// read as settled until counsel has reviewed them. The text comes from the content modules.
// `lead: null` drops the bold lead-in, for a page whose own facts are current (/contact, D8).
import { DRAFT_BANNER_LEAD } from "../content/legalDocument";

withDefaults(defineProps<{ text: string; lead?: string | null }>(), {
  lead: DRAFT_BANNER_LEAD,
});
</script>

<template>
  <p
    class="mt-6 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-900"
    data-testid="terms-draft-banner"
  >
    <span v-if="lead" class="font-semibold">{{ lead }}</span>
    {{ text }}
  </p>
</template>
