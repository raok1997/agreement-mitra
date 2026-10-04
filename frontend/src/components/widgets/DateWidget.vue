<script setup lang="ts">
// Date input (FieldType date), entered as dd/mm/yyyy on every host. A native <input type="date">
// renders in the host locale's order -- month-first on a US-set machine -- and no attribute or CSS
// can change that, so this is a masked text input with its own picker.
//
// The model always mirrors the text: "" when empty, ISO when the entry is a valid date, and the raw
// trimmed text otherwise, emitted on every input. The form's shared validation reports the raw text
// and refuses to save it, so no click or key path can save a value other than the one on screen.
// The widget owns no error state; it only defers SHOWING the error while someone is mid-typing.
import { computed, nextTick, ref, watch } from "vue";
import type { FormField } from "../../api/templateForm";
import { formatIso, parseEntry } from "../../views/dateEntry";
import DatePicker from "./DatePicker.vue";

const FULL_LENGTH = 10; // dd/mm/yyyy

const props = defineProps<{
  field: FormField;
  modelValue: string;
  error?: string | null;
}>();
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>();

function display(value: string): string {
  return formatIso(value) || (value ?? "");
}

const text = ref(display(props.modelValue));
let lastEmitted = props.modelValue ?? "";
const focused = ref(false);
const pickerOpen = ref(false);
const inputRef = ref<HTMLInputElement | null>(null);

// Only an outside change replaces the text; the echo of our own emit must not reformat mid-typing.
watch(
  () => props.modelValue,
  (value) => {
    if ((value ?? "") === lastEmitted) return;
    lastEmitted = value ?? "";
    text.value = display(lastEmitted);
  },
);

function commit(next: string): void {
  text.value = next;
  const trimmed = next.trim();
  const entry = parseEntry(trimmed);
  lastEmitted = !trimmed ? "" : entry.ok ? entry.iso : trimmed;
  emit("update:modelValue", lastEmitted);
}

function setText(next: string, caret: number): void {
  const input = inputRef.value;
  if (input) {
    input.value = next;
    input.setSelectionRange(caret, caret);
  }
  commit(next);
}

function onBeforeInput(e: InputEvent): void {
  if (e.inputType !== "insertText") return;
  const input = e.target as HTMLInputElement;
  const data = e.data ?? "";
  const current = input.value;
  const start = input.selectionStart ?? current.length;
  const end = input.selectionEnd ?? current.length;
  if (!/^[\d/]+$/.test(data)) {
    e.preventDefault();
    return;
  }
  if (current.length - (end - start) + data.length > FULL_LENGTH) {
    e.preventDefault();
    return;
  }
  // Lazy slash: a digit typed at the end of "dd" or "dd/mm" gets its separator first. Phone number
  // pads have no "/" key, so this is also how a backspaced slash comes back.
  const atEnd = start === current.length && end === current.length;
  if (atEnd && /^\d$/.test(data) && /^\d{2}(\/\d{2})?$/.test(current)) {
    e.preventDefault();
    const next = `${current}/${data}`;
    setText(next, next.length);
  }
}

function onPaste(e: ClipboardEvent): void {
  // Inserted whole: never stripped to its digits (2001-02-03 would become a real 20/01/0203) and
  // never truncated (08/01/20261 would become a valid 08/01/2026). A mismatch is reported instead.
  e.preventDefault();
  const pasted = (e.clipboardData?.getData("text") ?? "").trim();
  const input = e.target as HTMLInputElement;
  const current = input.value;
  const start = input.selectionStart ?? current.length;
  const end = input.selectionEnd ?? current.length;
  const next = current.slice(0, start) + pasted + current.slice(end);
  setText(next, start + pasted.length);
}

function onInput(e: Event): void {
  commit((e.target as HTMLInputElement).value);
}

function onBlur(): void {
  focused.value = false;
  const entry = parseEntry(text.value);
  if (entry.ok) text.value = formatIso(entry.iso);
}

// A half-typed "08/0" is someone typing, not a mistake: hold the message back until they leave the
// field or the entry reaches full length. Display only -- aria-invalid and the save block follow
// the prop regardless.
const showError = computed(
  () =>
    !!props.error && !(focused.value && text.value.length < FULL_LENGTH),
);

const hintId = computed(() => `date-hint-${props.field.key}`);
const errorId = computed(() => `date-error-${props.field.key}`);
const describedBy = computed(() =>
  showError.value ? `${hintId.value} ${errorId.value}` : hintId.value,
);

function closePicker(): void {
  pickerOpen.value = false;
  void nextTick(() => inputRef.value?.focus());
}

function onSelect(iso: string): void {
  commit(formatIso(iso));
  closePicker();
}

function onRootKeydown(e: KeyboardEvent): void {
  if (e.key !== "Escape" || !pickerOpen.value) return;
  e.preventDefault();
  e.stopPropagation();
  closePicker();
}
</script>

<template>
  <span class="flex flex-col" @keydown="onRootKeydown">
    <span class="flex items-stretch gap-1">
      <input
        ref="inputRef"
        type="text"
        inputmode="numeric"
        autocomplete="off"
        placeholder="dd/mm/yyyy"
        :value="text"
        :required="field.required"
        class="min-w-0 flex-1 rounded border px-3 py-2"
        :class="showError ? 'border-red-400' : 'border-slate-300'"
        :aria-label="field.label"
        :aria-describedby="describedBy"
        :aria-invalid="!!error"
        :data-testid="`field-${field.key}`"
        @beforeinput="onBeforeInput"
        @input="onInput"
        @paste="onPaste"
        @focus="focused = true"
        @blur="onBlur"
      />
      <button
        type="button"
        class="rounded border border-slate-300 px-2 text-slate-600 hover:bg-slate-100"
        :aria-label="`Choose ${field.label} from calendar`"
        :aria-expanded="pickerOpen"
        :data-testid="`date-picker-toggle-${field.key}`"
        @click="pickerOpen ? closePicker() : (pickerOpen = true)"
      >
        <svg
          aria-hidden="true"
          viewBox="0 0 20 20"
          class="h-4 w-4"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
        >
          <rect x="3" y="4.5" width="14" height="12" rx="1.5" />
          <path d="M3 8.5h14M7 3v3M13 3v3" />
        </svg>
      </button>
    </span>
    <span :id="hintId" class="mt-1 text-xs text-slate-500">dd/mm/yyyy</span>
    <DatePicker
      v-if="pickerOpen"
      :value="modelValue"
      :label="field.label"
      :field-key="field.key"
      @select="onSelect"
      @close="closePicker"
    />
    <span
      v-if="showError"
      :id="errorId"
      class="mt-1 text-xs text-red-600"
      :data-testid="`field-error-${field.key}`"
    >
      {{ error }}
    </span>
  </span>
</template>
