<script setup lang="ts">
// Closed-choice select (FieldType enum) over the field's options. Binds value + shows validation.
import type { FormField } from "../../api/templateForm";

defineProps<{ field: FormField; modelValue: string; error?: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();
</script>

<template>
  <span class="flex flex-col">
    <select
      :value="modelValue"
      :required="field.required"
      class="rounded border bg-white px-3 py-2"
      :class="error ? 'border-red-400' : 'border-slate-300'"
      :aria-invalid="!!error"
      :data-testid="`field-${field.key}`"
      @change="
        emit('update:modelValue', ($event.target as HTMLSelectElement).value)
      "
    >
      <option value="">{{ field.required ? "Select..." : "(none)" }}</option>
      <option
        v-for="opt in field.options ?? []"
        :key="opt.value"
        :value="opt.value"
      >
        {{ opt.label }}
      </option>
    </select>
    <span
      v-if="error"
      class="mt-1 text-xs text-red-600"
      :data-testid="`field-error-${field.key}`"
    >
      {{ error }}
    </span>
  </span>
</template>
