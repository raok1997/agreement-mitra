<script setup lang="ts">
// Multi-line text input. Binds its value and shows its field's validation error. Grows with its
// content (one row per line, plus one to type into) so a one-item-per-line list stays readable.
import { computed } from "vue";
import type { FormField } from "../../api/templateForm";

const MIN_ROWS = 3;
const MAX_ROWS = 14;

const props = defineProps<{
  field: FormField;
  modelValue: string;
  error?: string | null;
}>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();

const rows = computed(() => {
  const lines = (props.modelValue ?? "").split("\n").length + 1;
  return Math.min(MAX_ROWS, Math.max(MIN_ROWS, lines));
});
</script>

<template>
  <span class="flex flex-col">
    <textarea
      :value="modelValue"
      :rows="rows"
      :required="field.required"
      :maxlength="field.validation?.maxLength"
      class="resize-y rounded border px-3 py-2 leading-6"
      :class="error ? 'border-red-400' : 'border-slate-300'"
      :aria-invalid="!!error"
      :data-testid="`field-${field.key}`"
      @input="
        emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)
      "
    ></textarea>
    <span
      v-if="error"
      class="mt-1 text-xs text-red-600"
      :data-testid="`field-error-${field.key}`"
    >
      {{ error }}
    </span>
  </span>
</template>
