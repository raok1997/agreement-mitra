<script setup lang="ts">
import { ref } from "vue";
import { requestSignature, type SignSession } from "../api/client";

const agreementId = ref("demo-agreement-1");
const session = ref<SignSession | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);

// Responsive layout is pure Tailwind; the "reactive" part is just this state.
async function startSigning() {
  loading.value = true;
  error.value = null;
  try {
    session.value = await requestSignature(agreementId.value);
    // TODO: open session.value.signingUrl, then subscribe to status updates
    // (SSE/poll) so this view flips to "signed" when the webhook lands.
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="rounded-lg border bg-white p-6">
    <h2 class="mb-4 text-base font-medium">Aadhaar eSign demo</h2>
    <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
      <label class="flex-1">
        <span class="mb-1 block text-sm text-slate-600">Agreement ID</span>
        <input
          v-model="agreementId"
          class="w-full rounded border px-3 py-2"
          type="text"
        />
      </label>
      <button
        class="rounded bg-slate-900 px-4 py-2 text-white disabled:opacity-50"
        :disabled="loading"
        @click="startSigning"
      >
        {{ loading ? "Requesting..." : "Request signature" }}
      </button>
    </div>

    <p v-if="error" class="mt-4 text-sm text-red-600">{{ error }}</p>
    <div v-if="session" class="mt-4 rounded bg-slate-50 p-3 text-sm">
      <p>Signing URL:</p>
      <a :href="session.signingUrl" class="break-all text-blue-700 underline">
        {{ session.signingUrl }}
      </a>
    </div>
  </section>
</template>
