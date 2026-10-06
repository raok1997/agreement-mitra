<script setup lang="ts">
import { nextTick, onMounted, ref, useId } from "vue";

// A small modal confirmation. Rendered in place (no Teleport) so it stays inside its owner's DOM.
// Focus moves to Cancel on open - the safe choice for a destructive confirm - and Tab is trapped by
// a keydown handler; Escape and a click on the backdrop cancel. The panel itself is focusable
// (tabindex -1), so a click on its text keeps focus inside and the handler still sees the keys.
// The owner mounts it with v-if and decides where focus goes after it closes.
const props = withDefaults(
  defineProps<{
    title: string;
    confirmLabel?: string;
    cancelLabel?: string;
    busy?: boolean;
  }>(),
  { confirmLabel: "Confirm", cancelLabel: "Cancel", busy: false },
);

const emit = defineEmits<{
  (e: "confirm"): void;
  (e: "cancel"): void;
}>();

const titleId = useId();
const panel = ref<HTMLElement | null>(null);
const cancelButton = ref<HTMLButtonElement | null>(null);

function focusables(): HTMLElement[] {
  if (!panel.value) return [];
  return Array.from(
    panel.value.querySelectorAll<HTMLElement>(
      "button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex='-1'])",
    ),
  );
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    emit("cancel");
    return;
  }
  if (event.key !== "Tab") return;
  const items = focusables();
  if (!items.length) return;
  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;
  event.preventDefault();
  if (event.shiftKey) {
    (active === first || !items.includes(active as HTMLElement)
      ? last
      : items[items.indexOf(active as HTMLElement) - 1]
    ).focus();
  } else {
    (active === last || !items.includes(active as HTMLElement)
      ? first
      : items[items.indexOf(active as HTMLElement) + 1]
    ).focus();
  }
}

onMounted(async () => {
  await nextTick();
  cancelButton.value?.focus();
});
</script>

<template>
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
    data-testid="confirm-backdrop"
    @click.self="emit('cancel')"
  >
    <div
      ref="panel"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="titleId"
      tabindex="-1"
      class="w-full max-w-md rounded-lg bg-white p-5 shadow-xl"
      data-testid="confirm-dialog"
      @keydown="onKeydown"
    >
      <h3 :id="titleId" class="text-base font-semibold text-slate-900">
        {{ props.title }}
      </h3>
      <div class="mt-2 text-sm text-slate-600">
        <slot />
      </div>
      <div class="mt-5 flex justify-end gap-2">
        <button
          ref="cancelButton"
          type="button"
          class="rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
          data-testid="confirm-cancel"
          @click="emit('cancel')"
        >
          {{ props.cancelLabel }}
        </button>
        <button
          type="button"
          class="rounded bg-red-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
          :disabled="props.busy"
          data-testid="confirm-ok"
          @click="emit('confirm')"
        >
          {{ props.confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
