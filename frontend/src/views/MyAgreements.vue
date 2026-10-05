<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import {
  listMyAgreements,
  type AgreementStatus,
  type AgreementSummary,
} from "../api/agreements";
import { formatIso } from "./dateEntry";
import {
  editedAgo,
  formatInstantDate,
  formatRupees,
  matchesQuery,
} from "./agreementListFormat";

// The "My Agreements" (resume) list: the signed-in caller's agreements, most recently edited first
// (the server's order, kept as is), each with a derived status badge. "Edit" is offered only when the
// backend says the agreement is still editable (no signing request yet); a SIGNED one offers
// "View/Download" instead. Anonymous drafting never reaches this view -- it is mounted only for an
// authenticated session.
//
// The list, the search query and the open row live only in this component's state: never in the
// URL, history or browser storage, and dropped on unmount (which sign-out triggers).
const emit = defineEmits<{
  (e: "edit", id: string): void;
  (e: "view", id: string): void;
  (e: "new"): void;
}>();

const agreements = ref<AgreementSummary[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);
const query = ref("");
const openId = ref<string | null>(null);
const now = ref(new Date());

const visible = computed(() =>
  agreements.value.filter((a) => matchesQuery(a, query.value)),
);

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

function parties(a: AgreementSummary): { role: string; name: string }[] {
  return [
    ...a.ownerNames.map((name) => ({ role: "Owner", name })),
    ...a.tenantNames.map((name) => ({ role: "Tenant", name })),
  ];
}

function toggle(id: string): void {
  openId.value = openId.value === id ? null : id;
}

// Enter/Space on the row header itself; a key pressed on anything inside it is not a toggle.
function onHeaderKey(event: KeyboardEvent, id: string): void {
  if (event.target !== event.currentTarget) return;
  event.preventDefault();
  toggle(id);
}

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    agreements.value = await listMyAgreements();
    now.value = new Date();
  } catch {
    // Never surface server internals; a terse message only.
    error.value = "Could not load your agreements. Please try again.";
  } finally {
    loading.value = false;
  }
}

// Keeps "Edited …" current on a tab left open.
let clock: ReturnType<typeof setInterval> | undefined;

onMounted(() => {
  clock = setInterval(() => {
    now.value = new Date();
  }, 60_000);
  void load();
});
onUnmounted(() => clearInterval(clock));
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

    <template v-else>
      <label class="block">
        <span class="sr-only">Search agreements</span>
        <input
          v-model="query"
          type="search"
          placeholder="Search by owner, tenant, address or reference"
          class="w-full rounded-md border border-slate-300 px-3 py-2 text-sm"
          data-testid="list-search"
        />
      </label>

      <p
        v-if="!visible.length"
        class="rounded border border-dashed border-slate-300 px-4 py-8 text-center text-sm text-slate-500"
        data-testid="list-no-match"
      >
        No agreements match "{{ query.trim() }}". Try a name, the address or the
        reference.
      </p>

      <ul v-else class="flex flex-col gap-2" data-testid="list">
        <li
          v-for="a in visible"
          :key="a.id"
          class="rounded-md border border-slate-200"
          :data-testid="`row-${a.id}`"
        >
          <div class="flex items-start gap-3 px-4 py-3">
            <div
              role="button"
              tabindex="0"
              :aria-expanded="openId === a.id"
              class="grid min-w-0 flex-1 cursor-pointer grid-cols-1 gap-2 md:grid-cols-[minmax(0,1.25fr)_minmax(0,1.35fr)_minmax(0,0.95fr)_minmax(0,1fr)] md:gap-4"
              :data-testid="`toggle-${a.id}`"
              @click="toggle(a.id)"
              @keydown.enter="onHeaderKey($event, a.id)"
              @keydown.space="onHeaderKey($event, a.id)"
            >
              <div class="flex min-w-0 flex-col gap-0.5 text-sm">
                <p class="flex min-w-0 items-baseline gap-1.5">
                  <span class="w-14 flex-none text-xs uppercase text-slate-400"
                    >Owner</span
                  >
                  <span
                    class="truncate font-medium text-slate-800"
                    :title="a.ownerNames[0]"
                    :data-testid="`owner-${a.id}`"
                    >{{ a.ownerNames[0] ?? "—" }}</span
                  >
                  <span
                    v-if="a.ownerNames.length > 1"
                    class="flex-none rounded-full bg-indigo-50 px-1.5 text-xs font-semibold text-indigo-700"
                    :data-testid="`owner-more-${a.id}`"
                    >+{{ a.ownerNames.length - 1 }}</span
                  >
                </p>
                <p class="flex min-w-0 items-baseline gap-1.5">
                  <span class="w-14 flex-none text-xs uppercase text-slate-400"
                    >Tenant</span
                  >
                  <span
                    class="truncate text-slate-700"
                    :title="a.tenantNames[0]"
                    :data-testid="`tenant-${a.id}`"
                    >{{ a.tenantNames[0] ?? "—" }}</span
                  >
                  <span
                    v-if="a.tenantNames.length > 1"
                    class="flex-none rounded-full bg-indigo-50 px-1.5 text-xs font-semibold text-indigo-700"
                    :data-testid="`tenant-more-${a.id}`"
                    >+{{ a.tenantNames.length - 1 }}</span
                  >
                </p>
              </div>
              <p class="line-clamp-2 text-sm text-slate-600">
                {{ a.propertyAddress }}
              </p>
              <div
                class="flex flex-wrap items-baseline gap-x-2 text-sm md:flex-col"
              >
                <span class="font-medium text-slate-800"
                  >{{ formatRupees(a.monthlyRent) }}/mo</span
                >
                <span class="text-xs text-slate-500"
                  >{{ formatIso(a.startDate) }} –
                  {{ formatIso(a.endDate) }}</span
                >
              </div>
              <div
                class="flex flex-wrap items-center gap-2 md:flex-col md:items-start md:gap-1"
              >
                <span
                  class="rounded-full px-2 py-0.5 text-xs font-semibold"
                  :class="STATUS_CLASS[a.status]"
                  :data-testid="`status-${a.id}`"
                >
                  {{ STATUS_LABEL[a.status] }}
                </span>
                <span
                  class="text-xs text-slate-400"
                  :data-testid="`edited-${a.id}`"
                  >Edited {{ editedAgo(a.lastEditedAt, now) }}</span
                >
              </div>
            </div>
            <button
              v-if="a.editable"
              type="button"
              class="flex-none rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
              :data-testid="`edit-${a.id}`"
              @click.stop="emit('edit', a.id)"
            >
              Edit
            </button>
            <button
              v-else
              type="button"
              class="flex-none rounded border border-slate-300 px-3 py-1.5 text-sm font-medium"
              :data-testid="`view-${a.id}`"
              @click.stop="emit('view', a.id)"
            >
              View
            </button>
          </div>

          <div
            v-if="openId === a.id"
            class="grid grid-cols-1 gap-4 border-t border-dashed border-slate-200 bg-slate-50 px-4 py-3 text-sm md:grid-cols-2"
            :data-testid="`detail-${a.id}`"
          >
            <div>
              <h3 class="mb-2 text-xs font-semibold uppercase text-slate-500">
                Parties ({{ parties(a).length }})
              </h3>
              <ul class="flex flex-col gap-1">
                <li
                  v-for="(p, i) in parties(a)"
                  :key="i"
                  class="flex gap-3"
                  data-testid="detail-party"
                >
                  <span class="w-14 flex-none text-xs text-slate-500">{{
                    p.role
                  }}</span>
                  <span class="break-words">{{ p.name }}</span>
                </li>
              </ul>
            </div>
            <div>
              <h3 class="mb-2 text-xs font-semibold uppercase text-slate-500">
                Property
              </h3>
              <p class="mb-3 break-words" data-testid="detail-address">
                {{ a.propertyAddress }}
              </p>
              <dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
                <dt class="text-slate-500">Reference</dt>
                <dd data-testid="detail-reference">{{ a.trackingNumber }}</dd>
                <dt class="text-slate-500">Rent</dt>
                <dd>{{ formatRupees(a.monthlyRent) }} / month</dd>
                <dt class="text-slate-500">Term</dt>
                <dd>
                  {{ formatIso(a.startDate) }} – {{ formatIso(a.endDate) }} ({{
                    a.durationMonths
                  }}
                  months)
                </dd>
                <dt class="text-slate-500">Last edited</dt>
                <dd>{{ formatInstantDate(a.lastEditedAt) }}</dd>
              </dl>
            </div>
          </div>
        </li>
      </ul>
    </template>
  </section>
</template>
