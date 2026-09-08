<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { listTemplates, type TemplateSummary } from "../api/templateCatalog";
import { fetchEligibleOrNone } from "../api/jurisdictions";

// The State x Type picker: the entry step of the capture flow. Lists PUBLISHED catalog entries
// (template-catalog M4) and carries the chosen (state, type) into capture. It is a browse/select
// surface only -- it never fetches or holds user data (catalog rows are system-owned metadata).
//
// CONSTRAINT LIFTED (agreement-template-selection): every published template is now selectable.
// generate-as-draft became dimension-aware -- create records the selected published template and the
// signing draft path resolves + pins THAT effective template -- so the previewed document is the
// signed document for any published (state, type) (IN + TG), not just a single default. The former
// TG-only "Coming soon" lock is removed.
const emit = defineEmits<{
  (e: "select", dimensions: { state: string; type: string }): void;
}>();

const rows = ref<TemplateSummary[]>([]);
// null = "we could not find out", which marks NOTHING rather than marking everything draft-only.
// Enforcement is server-side either way; falsely telling an eligible customer they cannot be
// stamped would turn a transient network error into a lost sale.
const eligibleStates = ref<string[] | null>(null);
const loading = ref(true);
const error = ref<string | null>(null);

const query = ref("");
const typeFilter = ref("");
const stateFilter = ref("");

const types = computed(() =>
  [...new Set(rows.value.map((r) => r.type))].sort(),
);
const states = computed(() =>
  [...new Set(rows.value.map((r) => r.state))].sort(),
);

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase();
  return rows.value.filter((r) => {
    if (typeFilter.value && r.type !== typeFilter.value) return false;
    if (stateFilter.value && r.state !== stateFilter.value) return false;
    if (!q) return true;
    const hay = `${r.name} ${r.description ?? ""}`.toLowerCase();
    return hay.includes(q);
  });
});

/**
 * Whether this template can be stamped and eSigned, or only drafted and downloaded. Joined on the
 * state code with case normalised on both sides, so the join cannot fail on casing.
 */
function isDraftOnly(r: TemplateSummary): boolean {
  if (eligibleStates.value === null) return false;
  // Normalise BOTH sides here rather than trusting the api layer to have done it. The join is the
  // thing that must not fail on casing, so the guarantee belongs where the join happens - a
  // mismatch here would silently mislabel an eligible jurisdiction as draft-only.
  const eligible = eligibleStates.value.map((c) => c.trim().toUpperCase());
  return !eligible.includes(r.state.trim().toUpperCase());
}

function choose(r: TemplateSummary): void {
  emit("select", { state: r.state, type: r.type });
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    // Templates and eligibility are fetched together, but only the template list is allowed to
    // fail the screen: eligibility is disclosure, so it degrades to "mark nothing" on its own.
    const [templates, eligible] = await Promise.all([
      listTemplates(),
      fetchEligibleOrNone(),
    ]);
    rows.value = templates;
    eligibleStates.value = eligible;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "Could not load templates.";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div
    class="flex flex-col gap-4 rounded-lg border border-slate-200 bg-white p-4"
  >
    <div>
      <h2 class="text-base font-semibold text-slate-800">Choose a template</h2>
      <p class="text-sm text-slate-500">
        Pick the agreement type for your state. More states and types are on the
        way.
      </p>
    </div>

    <!-- Filters -->
    <div class="grid grid-cols-1 gap-2 sm:grid-cols-4">
      <input
        v-model="query"
        type="search"
        placeholder="Search templates"
        class="rounded border border-slate-300 px-3 py-2 text-sm sm:col-span-2"
        data-testid="picker-search"
      />
      <select
        v-model="stateFilter"
        class="rounded border border-slate-300 px-3 py-2 text-sm"
        data-testid="filter-state"
      >
        <option value="">All states</option>
        <option v-for="s in states" :key="s" :value="s">{{ s }}</option>
      </select>
      <select
        v-model="typeFilter"
        class="rounded border border-slate-300 px-3 py-2 text-sm"
        data-testid="filter-type"
      >
        <option value="">All types</option>
        <option v-for="t in types" :key="t" :value="t">{{ t }}</option>
      </select>
    </div>
    <!-- Language filter is reserved (English only) -- disabled per template-catalog. -->
    <select
      disabled
      class="w-full rounded border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-400 sm:w-40"
      data-testid="filter-language"
    >
      <option>English</option>
    </select>

    <p
      v-if="loading"
      class="py-6 text-sm text-slate-500"
      data-testid="picker-loading"
    >
      Loading templates...
    </p>
    <p
      v-else-if="error"
      class="py-6 text-sm text-red-600"
      data-testid="picker-error"
    >
      {{ error }}
    </p>
    <p
      v-else-if="filtered.length === 0"
      class="py-6 text-sm text-slate-500"
      data-testid="picker-empty"
    >
      No templates match your search.
    </p>

    <div
      v-else
      class="grid grid-cols-1 gap-3 sm:grid-cols-2"
      data-testid="picker-list"
    >
      <div
        v-for="r in filtered"
        :key="r.id"
        class="flex flex-col gap-2 rounded-md border border-slate-200 p-4"
        :data-testid="`template-card-${r.id}`"
      >
        <div class="flex items-start justify-between gap-2">
          <h3 class="text-sm font-semibold text-slate-800">{{ r.name }}</h3>
        </div>
        <p v-if="r.description" class="text-xs text-slate-500">
          {{ r.description }}
        </p>
        <div class="flex flex-wrap gap-1 text-xs text-slate-500">
          <span class="rounded bg-slate-100 px-2 py-0.5">{{ r.state }}</span>
          <span class="rounded bg-slate-100 px-2 py-0.5">{{ r.type }}</span>
          <span class="rounded bg-slate-100 px-2 py-0.5">v{{ r.version }}</span>
          <span
            v-if="isDraftOnly(r)"
            class="rounded bg-amber-100 px-2 py-0.5 font-medium text-amber-800"
            :data-testid="`draft-only-${r.id}`"
          >
            Draft &amp; download only
          </span>
        </div>
        <p
          v-if="isDraftOnly(r)"
          class="text-xs text-amber-700"
          :data-testid="`draft-only-note-${r.id}`"
        >
          You can fill this in, preview it and download it. Stamping and eSign
          are not yet available for this jurisdiction, so it cannot be paid for
          or signed here.
        </p>
        <button
          type="button"
          class="mt-1 rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white"
          :data-testid="`select-${r.id}`"
          @click="choose(r)"
        >
          {{ isDraftOnly(r) ? "Draft this template" : "Use this template" }}
        </button>
      </div>
    </div>
  </div>
</template>
