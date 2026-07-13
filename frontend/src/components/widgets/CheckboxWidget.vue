<script setup lang="ts">
// Boolean checkbox (FieldType bool). The working-set value is the string "true" / "" so it stays
// uniform with the other widgets; this maps it to a boolean at the input boundary.
import { computed } from "vue";
import type { FormField } from "../../api/templateForm";

const props = defineProps<{ field: FormField; modelValue: string; error?: string | null }>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();

const checked = computed({
  get: () => props.modelValue === "true",
  set: (v: boolean) => emit("update:modelValue", v ? "true" : ""),
});
</script>

<template>
  <span class="flex flex-col">
    <label class="flex items-center gap-2">
      <input
        type="checkbox"
        :checked="checked"
        class="h-4 w-4 rounded border-slate-300"
        :aria-invalid="!!error"
        :data-testid="`field-${field.key}`"
        @change="checked = ($event.target as HTMLInputElement).checked"
      />
      <span class="text-sm text-slate-700">{{ field.label }}</span>
    </label>
    <span v-if="error" class="mt-1 text-xs text-red-600" :data-testid="`field-error-${field.key}`">
      {{ error }}
    </span>
  </span>
</template>
