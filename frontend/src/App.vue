<script setup lang="ts">
import SignDemo from "./views/SignDemo.vue";
import wordmark from "./assets/logo-wordmark.svg";
</script>

<template>
  <div class="min-h-screen bg-ink-50 text-ink-800">
    <header class="border-b border-ink-200 bg-white">
      <div class="mx-auto flex max-w-3xl items-center justify-between gap-4 px-4 py-4">
        <img :src="wordmark" alt="AgreementMitra" class="h-8" />
        <span class="text-sm text-ink-500">Rental agreements made simple</span>
      </div>
    </header>
    <main class="mx-auto max-w-3xl px-4 py-8">
      <SignDemo />
    </main>
  </div>
</template>
