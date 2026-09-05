<script setup lang="ts">
// Money input (FieldType money): decimal amount with a currency affordance. Distinct from number so
// the client can format currency without re-inspecting the type. Binds value + shows validation.
import type { FormField } from "../../api/templateForm";

defineProps<{ field: FormField; modelValue: string; error?: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();
</script>

<template>
  <span class="flex flex-col">
    <span
      class="flex items-center rounded border px-2"
      :class="error ? 'border-red-400' : 'border-slate-300'"
    >
      <span class="pr-1 text-sm text-slate-500">Rs</span>
      <input
        type="number"
        step="0.01"
        inputmode="decimal"
        :value="modelValue"
        :required="field.required"
        :min="field.validation?.min"
        :max="field.validation?.max"
        class="w-full border-0 bg-transparent py-2 focus:outline-none"
        :aria-invalid="!!error"
        :data-testid="`field-${field.key}`"
        @input="
          emit('update:modelValue', ($event.target as HTMLInputElement).value)
        "
      />
    </span>
    <span
      v-if="error"
      class="mt-1 text-xs text-red-600"
      :data-testid="`field-error-${field.key}`"
    >
      {{ error }}
    </span>
  </span>
</template>
