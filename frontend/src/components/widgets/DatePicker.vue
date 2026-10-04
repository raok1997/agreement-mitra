<script setup lang="ts">
// Calendar picker for DateWidget. Written in-repo rather than taken from npm: the frontend OSV gate
// scans the whole lockfile, and a month grid with arrow-key movement does not justify a dependency.
// Renders inline (never teleported) so it stays inside the section modal's focus trap. All date
// arithmetic is UTC-based so the host time zone never shifts a day.
import { computed, nextTick, onMounted, ref } from "vue";
import {
  MAX_YEAR,
  MIN_YEAR,
  parseIso,
  toIso as iso,
  type DateParts,
} from "../../views/dateEntry";

const props = defineProps<{ value: string; label: string; fieldKey: string }>();
const emit = defineEmits<{
  (e: "select", iso: string): void;
  (e: "close"): void;
}>();

const MONTHS = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];
const WEEKDAYS = ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"];

function utc(p: DateParts): Date {
  return new Date(Date.UTC(p.year, p.month - 1, p.day));
}
function fromUtc(d: Date): DateParts {
  return { year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() };
}
function clamp(p: DateParts): DateParts {
  if (p.year < MIN_YEAR) return { year: MIN_YEAR, month: 1, day: 1 };
  if (p.year > MAX_YEAR) return { year: MAX_YEAR, month: 12, day: 31 };
  return p;
}
function today(): DateParts {
  const now = new Date();
  return { year: now.getFullYear(), month: now.getMonth() + 1, day: now.getDate() };
}
function daysIn(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

const selected = computed(() => {
  const p = parseIso(props.value);
  return p && p.year >= MIN_YEAR && p.year <= MAX_YEAR ? iso(p) : "";
});
const active = ref<DateParts>(clamp(parseIso(selected.value) ?? today()));

const heading = computed(
  () => `${MONTHS[active.value.month - 1]} ${active.value.year}`,
);
const atStart = computed(() => active.value.year === MIN_YEAR && active.value.month === 1);
const atEnd = computed(() => active.value.year === MAX_YEAR && active.value.month === 12);

/** The month as weeks of day numbers, Sunday first; null pads the days outside the month. */
const weeks = computed<(number | null)[][]>(() => {
  const { year, month } = active.value;
  const lead = utc({ year, month, day: 1 }).getUTCDay();
  const cells: (number | null)[] = Array(lead).fill(null);
  for (let d = 1; d <= daysIn(year, month); d++) cells.push(d);
  while (cells.length % 7) cells.push(null);
  const rows: (number | null)[][] = [];
  for (let i = 0; i < cells.length; i += 7) rows.push(cells.slice(i, i + 7));
  return rows;
});

const gridRef = ref<HTMLElement | null>(null);

function focusActive(): void {
  void nextTick(() => {
    gridRef.value
      ?.querySelector<HTMLElement>(`[data-iso="${iso(active.value)}"]`)
      ?.focus();
  });
}

function moveDays(n: number): void {
  const d = utc(active.value);
  d.setUTCDate(d.getUTCDate() + n);
  active.value = clamp(fromUtc(d));
  focusActive();
}

function moveMonths(n: number): void {
  const { year, month, day } = active.value;
  const index = year * 12 + (month - 1) + n;
  const target = { year: Math.floor(index / 12), month: (index % 12) + 1 };
  active.value = clamp({ ...target, day: Math.min(day, daysIn(target.year, target.month)) });
}

function onGridKeydown(e: KeyboardEvent): void {
  const steps: Record<string, () => void> = {
    ArrowLeft: () => moveDays(-1),
    ArrowRight: () => moveDays(1),
    ArrowUp: () => moveDays(-7),
    ArrowDown: () => moveDays(7),
    PageUp: () => { moveMonths(-1); focusActive(); },
    PageDown: () => { moveMonths(1); focusActive(); },
  };
  const step = steps[e.key];
  if (!step) return;
  e.preventDefault();
  step();
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key !== "Escape") return;
  // The section modal closes on a document-level Escape; this one belongs to the picker only.
  e.preventDefault();
  e.stopPropagation();
  emit("close");
}

function dayIso(day: number): string {
  return iso({ year: active.value.year, month: active.value.month, day });
}
function dayLabel(day: number): string {
  return `${day} ${heading.value}`;
}

onMounted(focusActive);
</script>

<template>
  <div
    class="mt-1 w-72 rounded border border-slate-300 bg-white p-2 shadow-lg"
    :data-testid="`date-picker-${fieldKey}`"
    @keydown="onKeydown"
  >
    <div class="mb-2 flex items-center justify-between">
      <button
        type="button"
        class="rounded px-2 py-1 text-slate-600 hover:bg-slate-100 disabled:opacity-30"
        aria-label="Previous month"
        :disabled="atStart"
        data-testid="date-picker-prev"
        @click="moveMonths(-1)"
      >
        &lsaquo;
      </button>
      <span class="text-sm font-medium" aria-live="polite">{{ heading }}</span>
      <button
        type="button"
        class="rounded px-2 py-1 text-slate-600 hover:bg-slate-100 disabled:opacity-30"
        aria-label="Next month"
        :disabled="atEnd"
        data-testid="date-picker-next"
        @click="moveMonths(1)"
      >
        &rsaquo;
      </button>
    </div>
    <div
      ref="gridRef"
      role="grid"
      :aria-label="`${label}, ${heading}`"
      class="text-center text-sm"
      @keydown="onGridKeydown"
    >
      <div role="row" class="grid grid-cols-7 text-xs text-slate-500">
        <span v-for="w in WEEKDAYS" :key="w" role="columnheader">{{ w }}</span>
      </div>
      <div
        v-for="(week, wi) in weeks"
        :key="wi"
        role="row"
        class="grid grid-cols-7"
      >
        <div
          v-for="(day, di) in week"
          :key="di"
          role="gridcell"
          :aria-selected="day !== null && dayIso(day) === selected"
        >
          <button
            v-if="day !== null"
            type="button"
            class="h-8 w-8 rounded"
            :class="
              dayIso(day) === selected
                ? 'bg-slate-800 text-white'
                : 'hover:bg-slate-100'
            "
            :tabindex="day === active.day ? 0 : -1"
            :aria-label="dayLabel(day)"
            :data-iso="dayIso(day)"
            @click="emit('select', dayIso(day))"
          >
            {{ day }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
