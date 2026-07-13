<script setup lang="ts">
import {
  reactive,
  ref,
  computed,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from "vue";
import {
  createAgreement,
  generateAgreementDocument,
  type CreateAgreementInput,
  type PartyInput,
  type Role,
} from "../api/client";
import {
  fetchDocumentPreviewHtml,
  fetchDocumentPreviewPdf,
} from "../api/documentPreview";
import {
  getTemplateForm,
  type FormDimensions,
  type FormField,
  type FormSchema,
} from "../api/templateForm";
import FieldWidget from "../components/widgets/FieldWidget.vue";
import {
  emptyWorking,
  fieldErrors,
  isSectionComplete,
  isSectionMandatory,
  reconcileActiveSections,
  sectionIcon,
  sectionId,
  type SectionData,
  type WorkingSet,
} from "./formModel";

// ---------------------------------------------------------------------------
// Selected dimensions. The State x Type picker (App.vue view-switch) passes the chosen (state, type)
// as props; they default to the seeded reference pair so the shell still resolves a template when
// mounted standalone.
//
// PARITY (agreement-template-selection): generate-as-draft is now DIMENSION-AWARE -- create records
// the selected published template and AgreementDocumentService resolves the SELECTED effective
// template for both the draft render and its pin. Previewing at the picked (state, type) therefore
// matches the signed draft for ANY published template (IN + TG), so the picker no longer locks to a
// single default. Template-declared fields the aggregate does not persist (see NON_PERSISTED_FIELDS)
// still render from the template's system-authored defaults on both faces -- parity holds.
// ---------------------------------------------------------------------------
const DEFAULT_STATE = "IN";
const DEFAULT_TYPE = "residential";

const props = withDefaults(defineProps<{ state?: string; type?: string }>(), {
  state: DEFAULT_STATE,
  type: DEFAULT_TYPE,
});
const emit = defineEmits<{ (e: "change-template"): void }>();

// The (state, type) the FormSchema is fetched for. The SAME dimensions are sent on every preview
// request so the previewed document resolves the exact effective template the form was projected from
// (flow-journal 8.2/8.3).
const previewDimensions = (): FormDimensions => ({
  state: props.state,
  type: props.type,
});

// ---------------------------------------------------------------------------
// Schema-fed section registry. The section rail / fields / widgets / validation / completeness are
// built from the fetched FormSchema (template-form-projection) instead of a hardcoded registry.
// The two-pane shell, section-modal flow, live preview pane, localStorage draft, and Save & continue
// persistence path are all preserved -- only the SOURCE of the sections changed.
// ---------------------------------------------------------------------------
const schema = ref<FormSchema | null>(null);
const schemaLoading = ref(true);
const schemaError = ref<string | null>(null);

// Working data store: reactive, section-keyed. Holds the in-progress agreement entirely in the
// browser -- NOTHING is persisted server-side until "Save & continue".
const working = reactive<WorkingSet>({});

interface UiSection {
  id: string;
  title: string;
  icon: string;
  fields: FormField[];
  // MANDATORY vs OPTIONAL from the schema (FormSection.optional, default false = mandatory), NOT from
  // field-level required-ness. Mandatory sections always render + count toward completeness; optional
  // sections are opt-in via the Add-optional catalog and never block save.
  optional: boolean;
}

// PARITY GUARDRAIL (2026-07-12, document-capture-shell-wiring task 4.2): generate-as-draft round-trips
// only the aggregate-backed field keys (signing AgreementDocumentMapper); a template-declared OPTIONAL
// field it does NOT persist would render from the effective template's DEFAULT in the signed draft
// while the live preview showed the user's value -- a silent preview/draft divergence. Until M5
// (attributes store + schema-driven, dimension-aware generate) lands, hide those non-persisted optional
// fields so the user cannot set a value the signed draft would ignore. These are also stripped from the
// preview data map below, so a resumed localStorage draft can never reintroduce them.
// stampDuty is TG-only, required, and NOT persisted on the aggregate; it renders from the template's
// system-authored default in BOTH the live preview and the signed draft (the aggregate never carries
// a user-set value), so hiding + stripping it keeps preview/draft parity until the attributes store
// (M5) lands.
const NON_PERSISTED_FIELDS = new Set([
  "furnished",
  "registrationResponsibility",
  "stampDuty",
]);

const uiSections = computed<UiSection[]>(() =>
  (schema.value?.sections ?? [])
    .map((s, i) => {
      const fields = s.fields.filter((f) => !NON_PERSISTED_FIELDS.has(f.key));
      return {
        id: sectionId(s.title, i),
        title: s.title,
        icon: sectionIcon(s.title),
        fields,
        optional: !isSectionMandatory(s),
      };
    })
    .filter((s) => s.fields.length > 0),
);

// The rail splits into two zones driven by the schema flag: MANDATORY sections (always present, count
// toward completeness) and OPTIONAL sections (opt-in via the Add-optional catalog, never block save).
const mandatorySections = computed(() =>
  uiSections.value.filter((s) => !s.optional),
);
const optionalSections = computed(() =>
  uiSections.value.filter((s) => s.optional),
);

// The added optional sections, tracked by TITLE (M2 keys the compiler's render decision on title). Sent
// on every preview POST so added optional content renders in preview + Download PDF and un-added content
// does not. Persisted with the client draft and reconciled against the schema on load.
const activeSections = ref<string[]>([]);
const activeOptionalSections = computed(() =>
  optionalSections.value.filter((s) => activeSections.value.includes(s.title)),
);
const availableOptionalSections = computed(() =>
  optionalSections.value.filter((s) => !activeSections.value.includes(s.title)),
);

function addOptionalSection(title: string): void {
  if (activeSections.value.includes(title)) return;
  activeSections.value = [...activeSections.value, title];
  persistDraft();
  schedulePreview();
}

function removeOptionalSection(title: string): void {
  // Non-destructive: drop the title from the active set (so it stops rendering) but keep any entered
  // field data in the client draft in case the user re-adds it.
  if (!activeSections.value.includes(title)) return;
  activeSections.value = activeSections.value.filter((t) => t !== title);
  persistDraft();
  schedulePreview();
}

const totalRequired = computed(() => mandatorySections.value.length);
const doneCount = computed(
  () =>
    mandatorySections.value.filter((s) =>
      isSectionComplete(s.fields, working[s.id] ?? {}),
    ).length,
);
const allRequiredDone = computed(
  () => totalRequired.value > 0 && doneCount.value === totalRequired.value,
);
// Number of mandatory sections still incomplete -- names N in the "complete N more required section(s)"
// affordance that gates Save & continue.
const remainingRequired = computed(() => totalRequired.value - doneCount.value);

function sectionById(id: string): UiSection | undefined {
  return uiSections.value.find((s) => s.id === id);
}
function isDone(s: UiSection): boolean {
  return isSectionComplete(s.fields, working[s.id] ?? {});
}
function summary(s: UiSection): string {
  const data = working[s.id] ?? {};
  for (const f of s.fields) {
    const v = (data[f.key] ?? "").trim();
    if (v && f.widget !== "checkbox") return v.split("\n")[0];
  }
  return "";
}

// ---------------------------------------------------------------------------
// Working-set flattening + submit bridge.
//
// PREVIEW is now schema-driven end-to-end: the live pane / Download PDF POST the flat working-set data
// map (field key -> value) straight to /api/templates/document/preview, so arbitrary template-declared
// fields render without any well-known-key remapping.
//
// SUBMIT is still a STOPGAP: Save & continue maps the generic working-set back into the existing typed
// create payload (fixed Agreement columns) by well-known field keys. Template-declared DYNAMIC fields
// (lockInMonths, petAllowed, ...) are therefore DROPPED on save until agreement-attributes-and-pinning
// (M5) lands the schema-driven, attribute-persisting submit + generate-as-draft pin. See flow-journal
// 8.4 (persistence gap) and 8.5 (preview/draft parity, owned by the documents window).
// PENDING-M5: replace buildAgreementInput/toParty with the schema-driven attribute submit.
// ---------------------------------------------------------------------------
function flatWorking(): Record<string, string> {
  const flat: Record<string, string> = {};
  for (const data of Object.values(working)) {
    for (const [k, v] of Object.entries(data)) {
      // Skip the non-persisted, hidden fields (parity guardrail) so they never reach the preview
      // (which must match the signed draft) nor the create payload -- even if a resumed localStorage
      // draft still carries a stale value for them.
      if (NON_PERSISTED_FIELDS.has(k)) continue;
      if (v != null && `${v}`.trim() !== "") flat[k] = `${v}`.trim();
    }
  }
  return flat;
}

function toParty(fullName: string, role: Role): PartyInput {
  const parts = fullName.trim().split(/\s+/);
  const firstName = parts.shift() ?? "";
  const lastName = parts.join(" ");
  const flat = flatWorking();
  return {
    firstName,
    lastName,
    fatherName: flat[`${role.toLowerCase()}FatherName`] ?? "",
    currentAddress: flat[`${role.toLowerCase()}Address`] ?? "",
    role,
  };
}

function buildAgreementInput(): CreateAgreementInput {
  const f = flatWorking();
  const signers: PartyInput[] = [];
  if (f.tenantName) signers.push(toParty(f.tenantName, "TENANT"));
  if (f.ownerName) signers.push(toParty(f.ownerName, "OWNER"));
  return {
    propertyAddress: f.propertyAddress ?? f.address ?? "",
    monthlyRent: f.monthlyRent ?? f.rent ?? "",
    securityDeposit: f.securityDeposit ?? f.deposit ?? "",
    startDate: f.startDate ?? "",
    endDate: f.endDate ?? "",
    signers,
    // Carry the picked dimensions so the server records the SELECTED published template and the
    // generated draft renders it (not the default). Same (state, type) the form + preview resolve.
    state: props.state,
    type: props.type,
  };
}

// ---------------------------------------------------------------------------
// Live preview: debounced call to the STATELESS preview endpoint, embedded as escaped HTML in a
// sandboxed iframe (no scripts). Nothing persisted.
// ---------------------------------------------------------------------------
const previewHtml = ref<string>("");
const previewLoading = ref(false);
const previewError = ref<string | null>(null);
let previewTimer: ReturnType<typeof setTimeout> | null = null;

function schedulePreview(): void {
  if (previewTimer) clearTimeout(previewTimer);
  previewTimer = setTimeout(refreshPreview, 250);
}

async function refreshPreview(): Promise<void> {
  previewLoading.value = true;
  previewError.value = null;
  try {
    previewHtml.value = await fetchDocumentPreviewHtml(
      flatWorking(),
      previewDimensions(),
      activeSections.value,
    );
  } catch (e) {
    // Never log the working set / rendered document (party PII); surface a terse message only.
    previewError.value =
      e instanceof Error ? e.message : "Preview unavailable.";
  } finally {
    previewLoading.value = false;
  }
}

// ---------------------------------------------------------------------------
// Section modal (focus-trap + Esc at every width; full-screen bottom sheet on phone).
// ---------------------------------------------------------------------------
const activeSectionId = ref<string | null>(null);
const activeSection = computed(() =>
  activeSectionId.value ? sectionById(activeSectionId.value) : undefined,
);
const modalForm = reactive<SectionData>({});
const modalErrors = computed(() =>
  activeSection.value ? fieldErrors(activeSection.value.fields, modalForm) : {},
);
const dialogRef = ref<HTMLElement | null>(null);
let lastFocused: HTMLElement | null = null;

function openSection(id: string): void {
  const s = sectionById(id);
  if (!s) return;
  lastFocused = document.activeElement as HTMLElement | null;
  activeSectionId.value = id;
  // Copy the current slice into the modal's local form (edits commit only on Save section).
  for (const key of Object.keys(modalForm)) delete modalForm[key];
  const slice = working[id] || {};
  for (const f of s.fields) modalForm[f.key] = slice[f.key] ?? "";
  void nextTick(() => {
    const first = dialogRef.value?.querySelector<HTMLElement>(
      "input,textarea,select",
    );
    first?.focus();
  });
}

function closeModal(): void {
  activeSectionId.value = null;
  lastFocused?.focus();
}

function saveSection(): void {
  const id = activeSectionId.value;
  if (!id) return;
  const slice: SectionData = {};
  for (const key of Object.keys(modalForm))
    slice[key] = (modalForm[key] ?? "").toString();
  working[id] = slice;
  closeModal();
  persistDraft();
  schedulePreview();
}

function onKeydown(e: KeyboardEvent): void {
  if (!activeSectionId.value) return;
  if (e.key === "Escape") {
    e.preventDefault();
    closeModal();
    return;
  }
  if (e.key === "Tab") {
    trapTab(e);
  }
}

function trapTab(e: KeyboardEvent): void {
  const root = dialogRef.value;
  if (!root) return;
  const focusable = root.querySelectorAll<HTMLElement>(
    'a[href],button:not([disabled]),input:not([disabled]),textarea:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])',
  );
  if (focusable.length === 0) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const current = document.activeElement as HTMLElement | null;
  if (e.shiftKey && current === first) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && current === last) {
    e.preventDefault();
    first.focus();
  }
}

// ---------------------------------------------------------------------------
// Client-side draft persistence (localStorage) for refresh-resume. This is client-side PII AT REST,
// so we clear it on a successful Save & continue and on explicit reset, and drop it if older than the
// TTL. "Persists nothing" is a SERVER-side statement; this is the bounded client-side exposure.
// ---------------------------------------------------------------------------
const DRAFT_KEY = "am.preview.draft.v1";
const DRAFT_TTL_MS = 24 * 60 * 60 * 1000;

function persistDraft(): void {
  try {
    localStorage.setItem(
      DRAFT_KEY,
      JSON.stringify({
        savedAt: Date.now(),
        data: working,
        activeSections: activeSections.value,
      }),
    );
  } catch {
    // Storage may be unavailable (private mode / quota); resume is best-effort.
  }
}

function loadDraft(): void {
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (!raw) return;
    const parsed = JSON.parse(raw) as {
      savedAt?: number;
      data?: WorkingSet;
      activeSections?: string[];
    };
    if (!parsed.savedAt || Date.now() - parsed.savedAt > DRAFT_TTL_MS) {
      clearDraft();
      return;
    }
    const data = parsed.data || {};
    // Merge only keys that exist in the current (schema-fed) working structure.
    for (const id of Object.keys(working)) {
      const stored = data[id];
      if (!stored) continue;
      for (const key of Object.keys(working[id])) {
        if (stored[key] != null) working[id][key] = stored[key];
      }
    }
    // Restore the added-optional set, dropping any title that is no longer an optional section in the
    // fetched schema (renamed / removed / now mandatory) -- so a stale title can never silently activate.
    if (Array.isArray(parsed.activeSections)) {
      activeSections.value = reconcileActiveSections(
        parsed.activeSections,
        schema.value,
      );
    }
  } catch {
    clearDraft();
  }
}

function clearDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY);
  } catch {
    // ignore
  }
}

function resetDraft(): void {
  const fresh = emptyWorking(schema.value);
  for (const id of Object.keys(working)) {
    working[id] = fresh[id] ?? {};
  }
  activeSections.value = [];
  clearDraft();
  schedulePreview();
}

// ---------------------------------------------------------------------------
// Final action: create + generate-as-draft via the EXISTING endpoints, then clear the client-held
// draft (Save & continue is the only thing that persists server-side).
// ---------------------------------------------------------------------------
const saving = ref(false);
const saved = ref(false);
const saveError = ref<string | null>(null);
const missingHint = ref<string | null>(null);

async function saveAndContinue(): Promise<void> {
  missingHint.value = null;
  saveError.value = null;
  // Defence-in-depth: the button is already :disabled while any mandatory section is incomplete, but a
  // programmatic click must still not save an incomplete set.
  if (!allRequiredDone.value) {
    missingHint.value = `Complete ${remainingRequired.value} more required section(s) first.`;
    return;
  }
  saving.value = true;
  try {
    const created = await createAgreement(buildAgreementInput());
    await generateAgreementDocument(created.id);
    saved.value = true;
    clearDraft(); // client-side PII cleared once it is safely persisted server-side
  } catch (e) {
    saved.value = false;
    saveError.value =
      e instanceof Error ? e.message : "Could not save. Please try again.";
  } finally {
    saving.value = false;
  }
}

// Download PDF: request the application/pdf variant and hand the browser a blob URL.
const downloading = ref(false);
async function downloadPdf(): Promise<void> {
  downloading.value = true;
  try {
    const blob = await fetchDocumentPreviewPdf(
      flatWorking(),
      previewDimensions(),
      activeSections.value,
    );
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "rental-agreement.pdf";
    a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    previewError.value =
      e instanceof Error ? e.message : "Download unavailable.";
  } finally {
    downloading.value = false;
  }
}

// Mobile: toggle between the sections rail and the preview pane.
const showPreviewMobile = ref(false);

async function loadSchema(): Promise<void> {
  schemaLoading.value = true;
  schemaError.value = null;
  try {
    const fetched = await getTemplateForm(props.state, props.type);
    schema.value = fetched;
    // Seed the working set from the schema, then resume any saved draft over it.
    const fresh = emptyWorking(fetched);
    for (const id of Object.keys(working)) delete working[id];
    Object.assign(working, fresh);
    loadDraft();
    void refreshPreview();
  } catch (e) {
    // Never echo the requested dimensions; the client already keeps them out of the message.
    schemaError.value =
      e instanceof Error ? e.message : "Could not load the form.";
  } finally {
    schemaLoading.value = false;
  }
}

onMounted(() => {
  document.addEventListener("keydown", onKeydown);
  void loadSchema();
});
onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKeydown);
  if (previewTimer) clearTimeout(previewTimer);
});
</script>

<template>
  <div
    class="flex min-h-[80vh] flex-col overflow-hidden rounded-lg border border-slate-200 bg-white lg:h-[calc(100vh-7rem)] lg:min-h-0"
  >
    <!-- Top bar: completeness meter + actions -->
    <header
      class="flex flex-wrap items-center gap-3 border-b border-slate-200 px-4 py-2"
    >
      <div
        class="flex min-w-[180px] flex-1 items-center gap-3"
        data-testid="completeness"
      >
        <div class="h-2 flex-1 overflow-hidden rounded-full bg-slate-100">
          <div
            class="h-full rounded-full bg-slate-900 transition-all"
            :style="{
              width: `${totalRequired ? Math.round((doneCount / totalRequired) * 100) : 0}%`,
            }"
          ></div>
        </div>
        <span class="whitespace-nowrap text-sm text-slate-600">
          <b class="text-slate-900">{{ doneCount }}</b> of
          <b class="text-slate-900">{{ totalRequired }}</b> required sections
          ready
        </span>
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          class="rounded border border-slate-300 px-3 py-2 text-sm font-medium"
          data-testid="change-template"
          @click="emit('change-template')"
        >
          Change template
        </button>
        <button
          type="button"
          class="rounded border border-slate-300 px-3 py-2 text-sm font-medium lg:hidden"
          data-testid="mobile-toggle"
          @click="showPreviewMobile = !showPreviewMobile"
        >
          {{ showPreviewMobile ? "Sections" : "Preview" }}
        </button>
        <button
          type="button"
          class="rounded border border-slate-300 px-3 py-2 text-sm font-medium disabled:opacity-50"
          :disabled="downloading"
          data-testid="download-pdf"
          @click="downloadPdf"
        >
          {{ downloading ? "Preparing..." : "Download PDF" }}
        </button>
        <button
          type="button"
          class="rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
          :disabled="saving || !allRequiredDone"
          data-testid="save-continue"
          @click="saveAndContinue"
        >
          {{ saving ? "Saving..." : "Save & continue" }}
        </button>
      </div>
    </header>

    <div class="grid flex-1 lg:min-h-0 lg:grid-cols-[minmax(320px,420px)_1fr]">
      <!-- Section rail -->
      <aside
        v-show="!showPreviewMobile"
        class="border-b border-slate-200 lg:min-h-0 lg:overflow-y-auto lg:border-b-0 lg:border-r"
        data-testid="section-rail"
      >
        <p
          v-if="schemaLoading"
          class="px-4 py-6 text-sm text-slate-500"
          data-testid="schema-loading"
        >
          Loading the form...
        </p>
        <p
          v-else-if="schemaError"
          class="px-4 py-6 text-sm text-red-600"
          data-testid="schema-error"
        >
          {{ schemaError }}
        </p>

        <div v-else class="px-3 py-2">
          <!-- Mandatory sections: always present, count toward completeness, show Ready / Needs input. -->
          <p
            class="px-1 pb-1 pt-1 text-xs font-semibold uppercase tracking-wide text-slate-400"
          >
            Required sections
          </p>
          <div class="flex flex-col gap-2">
            <button
              v-for="s in mandatorySections"
              :key="s.id"
              type="button"
              class="flex items-center gap-3 rounded-md border border-slate-200 px-3 py-2 text-left hover:border-slate-400 hover:bg-slate-50"
              :data-testid="`section-${s.id}`"
              @click="openSection(s.id)"
            >
              <span
                class="grid h-8 w-8 flex-none place-items-center rounded-md border text-xs"
                :class="
                  isDone(s)
                    ? 'border-green-200 bg-green-50'
                    : 'border-slate-200 bg-slate-50'
                "
              >
                {{ s.icon }}
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm font-medium text-slate-800">{{
                  s.title
                }}</span>
                <span
                  class="block truncate text-xs"
                  :class="
                    summary(s) ? 'text-slate-500' : 'italic text-slate-400'
                  "
                >
                  {{ summary(s) || "required - tap to fill" }}
                </span>
              </span>
              <span
                class="flex-none rounded-full px-2 py-0.5 text-xs font-semibold"
                :class="
                  isDone(s)
                    ? 'bg-green-50 text-green-700'
                    : 'bg-amber-50 text-amber-700'
                "
                :data-testid="`status-${s.id}`"
              >
                {{ isDone(s) ? "Ready" : "Needs input" }}
              </span>
            </button>
          </div>

          <!-- Active optional sections: added from the catalog, edit like any section, offer Remove. -->
          <div
            v-if="activeOptionalSections.length"
            class="mt-4 flex flex-col gap-2"
          >
            <p
              class="px-1 text-xs font-semibold uppercase tracking-wide text-slate-400"
            >
              Added optional sections
            </p>
            <div
              v-for="s in activeOptionalSections"
              :key="s.id"
              class="flex items-center gap-3 rounded-md border border-slate-200 px-3 py-2"
              :data-testid="`active-optional-${s.id}`"
            >
              <button
                type="button"
                class="flex min-w-0 flex-1 items-center gap-3 text-left"
                :data-testid="`section-${s.id}`"
                @click="openSection(s.id)"
              >
                <span
                  class="grid h-8 w-8 flex-none place-items-center rounded-md border border-slate-200 bg-slate-50 text-xs"
                >
                  {{ s.icon }}
                </span>
                <span class="min-w-0 flex-1">
                  <span class="block text-sm font-medium text-slate-800">{{
                    s.title
                  }}</span>
                  <span
                    class="block truncate text-xs"
                    :class="
                      summary(s) ? 'text-slate-500' : 'italic text-slate-400'
                    "
                  >
                    {{ summary(s) || "optional - tap to fill" }}
                  </span>
                </span>
              </button>
              <button
                type="button"
                class="flex-none rounded-full px-2 py-0.5 text-xs font-semibold text-slate-500 hover:text-red-600"
                :data-testid="`remove-optional-${s.id}`"
                @click="removeOptionalSection(s.title)"
              >
                Remove
              </button>
            </div>
          </div>

          <!-- Add-optional catalog: optional sections not yet added, opt-in via the Add affordance. -->
          <div
            v-if="availableOptionalSections.length"
            class="mt-4 flex flex-col gap-2"
          >
            <p
              class="px-1 text-xs font-semibold uppercase tracking-wide text-slate-400"
            >
              Add optional
            </p>
            <div
              v-for="s in availableOptionalSections"
              :key="s.id"
              class="flex items-center gap-3 rounded-md border border-dashed border-slate-300 px-3 py-2"
              :data-testid="`catalog-${s.id}`"
            >
              <span
                class="grid h-8 w-8 flex-none place-items-center rounded-md border border-slate-200 bg-white text-xs"
              >
                {{ s.icon }}
              </span>
              <span class="min-w-0 flex-1">
                <span class="block text-sm font-medium text-slate-700">{{
                  s.title
                }}</span>
                <span class="block truncate text-xs italic text-slate-400">optional</span>
              </span>
              <button
                type="button"
                class="flex-none rounded-full border border-slate-300 px-3 py-0.5 text-xs font-semibold text-slate-700 hover:border-slate-500 hover:bg-slate-50"
                :data-testid="`add-optional-${s.id}`"
                @click="addOptionalSection(s.title)"
              >
                Add
              </button>
            </div>
          </div>
        </div>

        <div v-if="schema" class="px-4 py-3">
          <button
            type="button"
            class="text-xs text-slate-400 underline"
            data-testid="reset"
            @click="resetDraft"
          >
            Reset draft
          </button>
        </div>
      </aside>

      <!-- Live preview pane -->
      <section
        :class="showPreviewMobile ? 'block' : 'hidden lg:block'"
        class="relative bg-slate-50 lg:flex lg:min-h-0 lg:flex-col"
        data-testid="preview-pane"
      >
        <div
          class="sticky top-0 z-[1] flex items-center gap-2 border-b border-slate-200 bg-slate-50/90 px-4 py-2 backdrop-blur lg:shrink-0"
        >
          <span
            class="flex items-center gap-2 text-xs font-semibold text-slate-500"
          >
            <span class="h-2 w-2 rounded-full bg-green-500"></span> Live preview
          </span>
          <span
            v-if="previewLoading"
            class="text-xs text-slate-400"
            data-testid="preview-loading"
          >
            refreshing...
          </span>
          <span
            v-if="previewError"
            class="text-xs text-red-600"
            data-testid="preview-error"
          >
            {{ previewError }}
          </span>
        </div>
        <div class="p-4 lg:flex lg:min-h-0 lg:flex-1 lg:flex-col">
          <iframe
            :srcdoc="previewHtml"
            title="Live rental agreement preview"
            sandbox=""
            class="h-[70vh] w-full rounded border border-slate-200 bg-white lg:h-auto lg:min-h-0 lg:flex-1"
            data-testid="preview-frame"
          ></iframe>
        </div>
      </section>
    </div>

    <p
      v-if="missingHint"
      class="border-t border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-800"
      data-testid="missing-hint"
    >
      {{ missingHint }}
    </p>
    <p
      v-if="saveError"
      class="border-t border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
      data-testid="save-error"
    >
      {{ saveError }}
    </p>
    <p
      v-if="saved && !saveError"
      class="border-t border-green-200 bg-green-50 px-4 py-2 text-sm text-green-700"
      data-testid="save-ok"
    >
      Agreement saved and its draft generated - ready for signing.
    </p>
  </div>

  <!-- Section modal (focus-trapped; full-screen bottom sheet on phone) -->
  <div
    v-if="activeSection"
    class="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 p-0 sm:items-center sm:p-6"
    data-testid="section-modal"
    @click.self="closeModal"
  >
    <div
      ref="dialogRef"
      role="dialog"
      aria-modal="true"
      :aria-label="activeSection.title"
      class="max-h-[92vh] w-full overflow-y-auto rounded-t-xl bg-white shadow-xl sm:max-w-lg sm:rounded-xl"
    >
      <header
        class="flex items-start gap-3 border-b border-slate-200 px-5 py-4"
      >
        <span
          class="grid h-8 w-8 place-items-center rounded-md bg-slate-100 text-xs"
        >
          {{ activeSection.icon }}
        </span>
        <div class="flex-1">
          <h3 class="text-base font-semibold text-slate-800">
            {{ activeSection.title }}
          </h3>
          <p class="text-xs text-slate-500">
            {{
              activeSection.optional
                ? "Optional - appears once added."
                : "Required - appears in the document."
            }}
          </p>
        </div>
        <button
          type="button"
          class="rounded px-2 text-slate-400 hover:text-slate-700"
          aria-label="Close"
          data-testid="modal-close"
          @click="closeModal"
        >
          x
        </button>
      </header>
      <div class="grid grid-cols-1 gap-3 px-5 py-4 sm:grid-cols-2">
        <div
          v-for="f in activeSection.fields"
          :key="f.key"
          class="flex flex-col text-sm"
          :class="f.widget === 'textarea' ? 'sm:col-span-2' : ''"
        >
          <span
            v-if="f.widget !== 'checkbox'"
            class="mb-1 text-xs font-medium text-slate-600"
          >
            {{ f.label }}<span v-if="f.required" class="text-red-500"> *</span>
          </span>
          <FieldWidget
            v-model="modalForm[f.key]"
            :field="f"
            :error="modalErrors[f.key]"
          />
        </div>
      </div>
      <footer
        class="sticky bottom-0 flex items-center justify-end gap-2 border-t border-slate-200 bg-white px-5 py-4"
      >
        <span class="mr-auto text-xs text-slate-400">escaped & sandboxed - you edit data, never markup</span>
        <button
          type="button"
          class="rounded border border-slate-300 px-4 py-2 text-sm"
          data-testid="modal-cancel"
          @click="closeModal"
        >
          Cancel
        </button>
        <button
          type="button"
          class="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white"
          data-testid="modal-save"
          @click="saveSection"
        >
          Save section
        </button>
      </footer>
    </div>
  </div>
</template>
