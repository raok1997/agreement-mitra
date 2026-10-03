<script setup lang="ts">
// Read-only display for a SERVER-DERIVED field (FormField.readOnly): the server computes the value
// from other captured values, so the customer sees it but never edits it. Not an input at all --
// there is no <input>, so there is nothing to tab into, type in, or submit. The value shown is the
// one the rendered document will state.
//
// An empty value means "not yet determined" (the inputs it derives from are incomplete) rather than
// "blank": showing a stale or defaulted number would be worse than admitting it is not known yet.
import { computed } from "vue";
import type { FormField } from "../../api/templateForm";

const props = defineProps<{
  field: FormField;
  modelValue: string;
  error?: string | null;
}>();

const determined = computed(() => (props.modelValue ?? "").trim() !== "");
</script>

<template>
  <span class="flex flex-col">
    <span
      class="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-slate-700"
      :data-testid="`field-${field.key}`"
      aria-readonly="true"
    >
      <template v-if="determined">{{ modelValue }}</template>
      <span v-else class="text-slate-400">Not yet determined</span>
    </span>
    <span class="mt-1 text-xs text-slate-500">Calculated automatically</span>
    <span
      v-if="error"
      class="mt-1 text-xs text-red-600"
      :data-testid="`field-error-${field.key}`"
    >
      {{ error }}
    </span>
  </span>
</template>
