<script setup lang="ts">
// Dispatcher: renders the right widget component for a field's resolved `widget` token. Keeps the
// capture shell agnostic of the widget vocabulary -- a new widget is one entry here + one component.
import { computed, type Component } from "vue";
import type { FormField } from "../../api/templateForm";
import TextWidget from "./TextWidget.vue";
import TextareaWidget from "./TextareaWidget.vue";
import NumberWidget from "./NumberWidget.vue";
import MoneyWidget from "./MoneyWidget.vue";
import DateWidget from "./DateWidget.vue";
import CheckboxWidget from "./CheckboxWidget.vue";
import SelectWidget from "./SelectWidget.vue";

const props = defineProps<{
  field: FormField;
  modelValue: string;
  error?: string | null;
}>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();

const registry: Record<string, Component> = {
  text: TextWidget,
  textarea: TextareaWidget,
  number: NumberWidget,
  money: MoneyWidget,
  date: DateWidget,
  checkbox: CheckboxWidget,
  select: SelectWidget,
};

const widget = computed<Component>(
  () => registry[props.field.widget] ?? TextWidget,
);
</script>

<template>
  <component
    :is="widget"
    :field="field"
    :model-value="modelValue"
    :error="error"
    @update:model-value="emit('update:modelValue', $event)"
  />
</template>
