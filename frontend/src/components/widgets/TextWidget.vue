<script setup lang="ts">
// Single-line text input. Binds its value and shows its field's validation error.
import type { FormField } from "../../api/templateForm";

defineProps<{ field: FormField; modelValue: string; error?: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();
</script>

<template>
  <span class="flex flex-col">
    <input
      type="text"
      :value="modelValue"
      :required="field.required"
      :maxlength="field.validation?.maxLength"
      class="rounded border px-3 py-2"
      :class="error ? 'border-red-400' : 'border-slate-300'"
      :aria-invalid="!!error"
      :data-testid="`field-${field.key}`"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <span v-if="error" class="mt-1 text-xs text-red-600" :data-testid="`field-error-${field.key}`">
      {{ error }}
    </span>
  </span>
</template>
