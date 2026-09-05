<script setup lang="ts">
// The SPA landing target of the Google callback. The backend 302s here with the single-use handoff
// in the URL fragment (never a token, never the session). We read it, exchange it for a session, and
// signal the app to return to the main flow. The fragment is dropped from history on success.
import { onMounted, ref } from "vue";
import { completeLogin } from "../api/authStore";

const emit = defineEmits<{ done: [] }>();
const status = ref<"working" | "error">("working");

onMounted(async () => {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const handoff = params.get("handoff");
  if (!handoff) {
    status.value = "error";
    return;
  }
  try {
    await completeLogin(handoff);
    emit("done");
  } catch {
    // Leave the error UI up (with a link home); do not signal success.
    status.value = "error";
  }
});
</script>

<template>
  <div
    class="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-16 text-center"
  >
    <p v-if="status === 'working'" class="text-ink-600">
      Signing you in&hellip;
    </p>
    <template v-else>
      <p class="text-ink-800">We couldn't complete sign-in.</p>
      <a href="/" class="text-sm font-medium text-sky-700 hover:underline"
        >Back to AgreementMitra</a
      >
    </template>
  </div>
</template>
