<script setup lang="ts">
import { ref } from "vue";
import CaptureForm from "./views/CaptureForm.vue";
import TemplatePicker from "./components/TemplatePicker.vue";
import wordmark from "./assets/logo-wordmark.svg";

// Lightweight view-switch (no vue-router -- see flow-journal 8.3): the picker is the entry step, and
// on selection we mount the capture shell with the chosen (state, type) as props. "Change template"
// clears the selection and returns to the picker. Keeping this router-less avoids widening the
// frontend dependency/OSV surface for a two-view flow.
const selection = ref<{ state: string; type: string } | null>(null);

function onSelect(dimensions: { state: string; type: string }): void {
  selection.value = dimensions;
}
function onChangeTemplate(): void {
  selection.value = null;
}
</script>

<template>
  <div class="min-h-screen bg-ink-50 text-ink-800">
    <header class="border-b border-ink-200 bg-white">
      <div class="mx-auto flex max-w-3xl items-center justify-between gap-4 px-4 py-2.5">
        <img :src="wordmark" alt="AgreementMitra" class="h-8" />
        <span class="text-sm text-ink-500">Rental agreements made simple</span>
      </div>
    </header>
    <main class="mx-auto flex max-w-7xl flex-col gap-1.5 px-4 pb-3 pt-2">
      <h1 class="text-lg font-semibold text-ink-800">Create a Rental Agreement</h1>
      <TemplatePicker v-if="!selection" @select="onSelect" />
      <CaptureForm
        v-else
        :state="selection.state"
        :type="selection.type"
        @change-template="onChangeTemplate"
      />
    </main>
  </div>
</template>
