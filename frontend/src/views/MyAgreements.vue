<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  listMyAgreements,
  type AgreementStatus,
  type AgreementSummary,
} from "../api/agreements";

// The "My Agreements" (resume) list: the signed-in caller's agreements, most-recent first, each with
// a derived status badge. "Edit" is offered only when the backend says the agreement is still editable
// (no signing request yet); a SIGNED one offers "View/Download" instead. Anonymous drafting never
// reaches this view -- it is mounted only for an authenticated session.
const emit = defineEmits<{
  (e: "edit", id: string): void;
  (e: "view", id: string): void;
  (e: "new"): void;
}>();

const agreements = ref<AgreementSummary[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

const STATUS_LABEL: Record<AgreementStatus, string> = {
  DRAFT: "Draft",
  IN_PROGRESS: "In progress",
  SIGNED: "Signed",
  EXPIRED: "Expired",
  ACTION_NEEDED: "Action needed",
};

// Tailwind badge classes per status -- neutral for draft, amber in-progress, green signed, red trouble.
const STATUS_CLASS: Record<AgreementStatus, string> = {
  DRAFT: "bg-slate-100 text-slate-700",
  IN_PROGRESS: "bg-amber-50 text-amber-700",
  SIGNED: "bg-green-50 text-green-700",
  EXPIRED: "bg-slate-100 text-slate-500",
  ACTION_NEEDED: "bg-red-50 text-red-700",
};

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    agreements.value = await listMyAgreements();
  } catch {
    // Never surface server internals; a terse message only.
    error.value = "Could not load your agreements. Please try again.";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="flex flex-col gap-3" data-testid="my-agreements">
    <div class="flex items-center justify-between">
      <h2 class="text-base font-semibold text-slate-800">My agreements</h2>
      <button
        type="button"
        class="rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white"
        data-testid="new-agreement"
        @click="emit('new')"
      >
        New agreement
      </button>
    </div>

    <p
      v-if="loading"
      class="py-6 text-sm text-slate-500"
      data-testid="list-loading"
    >
      Loading your agreements...
    </p>
    <p
      v-else-if="error"
      class="py-6 text-sm text-red-600"
      data-testid="list-error"
    >
      {{ error }}
    </p>
    <p
      v-else-if="!agreements.length"
      class="rounded border border-dashed border-slate-300 px-4 py-8 text-center text-sm text-slate-500"
      data-testid="list-empty"
    >
      You have no saved agreements yet. Create one, then Save it to see it here.
    </p>

    <ul v-else class="flex flex-col gap-2" data-testid="list">
      <li
        v-for="a in agreements"
        :key="a.id"
        class="flex flex-wrap items-center gap-3 rounded-md border border-slate-200 px-4 py-3"
        :data-testid="`row-${a.id}`"
      >
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-medium text-slate-800">
            {{ a.propertyAddress }}
          </p>
          <p class="text-xs text-slate-500">
            {{ a.trackingNumber }} &middot; {{ a.durationMonths }} months
          </p>
        </div>
        <span
          class="rounded-full px-2 py-0.5 text-xs font-semibold"
          :class="STATUS_CLASS[a.status]"
          :data-testid="`status-${a.id}`"
        >
          {{ STATUS_LABEL[a.status] }}
        </span>
        <button
          v-if="a.editable"
          type="button"
          class="rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
          :data-testid="`edit-${a.id}`"
          @click="emit('edit', a.id)"
        >
          Edit
        </button>
        <button
          v-else
          type="button"
          class="rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
          :data-testid="`view-${a.id}`"
          @click="emit('view', a.id)"
        >
          View
        </button>
      </li>
    </ul>
  </section>
</template>
