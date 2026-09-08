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
  type AgreementView,
  type CreateAgreementInput,
  type PartyInput,
  type Role,
} from "../api/client";
import {
  AgreementHttpError,
  claimAgreement,
  finaliseAgreement,
  getAgreement,
  updateAgreement,
  updateAgreementContacts,
} from "../api/agreements";
import ContactConfirmation, {
  type PartyContact,
} from "./ContactConfirmation.vue";
import PaymentConfirmation from "./PaymentConfirmation.vue";
import LegalDisclaimer from "../components/LegalDisclaimer.vue";
import { fetchEligibleOrNone } from "../api/jurisdictions";
import {
  formatMinorUnits,
  getPaymentProgress,
  payForAgreement,
} from "../api/payments";
import { auth } from "../api/authStore";
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
// Whether this agreement's jurisdiction can actually be stamped and eSigned, or is draft-only.
// null = "we could not find out", which discloses NOTHING rather than warning wrongly: enforcement
// is server-side either way, and telling an eligible customer they cannot be stamped would be worse
// than staying quiet. Fetched here as well as in the picker because a customer can arrive at this
// shell directly (a recovery link, a resumed draft) without passing the picker.
const eligibleStates = ref<string[] | null>(null);

/**
 * Whether this agreement can be paid for and signed here, or only drafted and downloaded.
 *
 * The jurisdiction is taken from the REOPENED AGREEMENT first and only then from the prop. That
 * order is the whole point: `state` has a default, so on the edit path the prop is not the
 * agreement's jurisdiction at all -- it is DEFAULT_STATE. Reading it alone labelled every reopened
 * agreement draft-only, including a perfectly stampable Telangana one, which is the one outcome
 * the disclosure must never produce: mislabelling an eligible jurisdiction deters a customer we
 * could in fact have served. `AgreementView.state` is resolved server-side from the pinned
 * template, so it is the same value the server's gate refuses on.
 *
 * An agreement with no resolvable pinned template reports no state and falls back to the default:
 * correct here rather than merely convenient, because the server refuses that agreement as an
 * unknown jurisdiction too.
 */
const jurisdictionOfThisAgreement = computed(
  () => props.initialAgreement?.state ?? props.state,
);

const isDraftOnlyJurisdiction = computed(() => {
  if (eligibleStates.value === null) return false;
  const eligible = eligibleStates.value.map((c) => c.trim().toUpperCase());
  return !eligible.includes(
    (jurisdictionOfThisAgreement.value ?? "").trim().toUpperCase(),
  );
});

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

const props = withDefaults(
  defineProps<{
    state?: string;
    type?: string;
    // Edit mode (agreement-ownership CR): when an owned agreement is being edited, its id + loaded
    // terms are passed in. Save then PUTs to /api/agreements/{id} instead of creating a new one.
    agreementId?: string;
    initialAgreement?: AgreementView | null;
  }>(),
  {
    state: DEFAULT_STATE,
    type: DEFAULT_TYPE,
    agreementId: undefined,
    initialAgreement: null,
  },
);
const emit = defineEmits<{
  (e: "change-template"): void;
  // Fired after a successful Save-to-account (claim) or an edit save, so the shell can return to the
  // "My Agreements" list.
  (e: "saved-to-account"): void;
}>();

// True while editing an existing owned agreement (vs. drafting a new one).
const editMode = computed(() => !!props.agreementId);

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

// M5 (agreement-capture-persistence) retired the STOPGAP: the agreement now persists its FULL capture
// state (the flat working-set map + added optional sections), and generate-as-draft renders from that
// stored state, so every user-set field round-trips and the signed draft matches the live preview.
// The hide-list therefore shrinks to only GENUINELY system-owned template-default fields the user
// never sets -- stampDuty is TG-only, required, and rendered from the template's system-authored
// default on BOTH faces (the user never enters a value), so hiding + stripping it keeps parity without
// data loss. Everything the aggregate now persists (furnished, registrationResponsibility, dynamic
// fields like lockInMonths / petAllowed) is un-hidden -- hiding them would re-introduce the very data
// loss this change removed (design D5).
const NON_PERSISTED_FIELDS = new Set(["stampDuty"]);

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
// SUBMIT now persists the FULL capture state (M5, agreement-capture-persistence): Save & continue
// sends the typed fixed columns (property/rent/deposit/dates/parties) AND the flat working-set map
// (captureData) + added optional sections (activeSections). Template-declared DYNAMIC fields
// (lockInMonths, petAllowed, ...) therefore round-trip and render in the stored/signed draft, matching
// the preview. The fixed typed columns stay authoritative server-side (a stale map entry never
// overrides them); server-managed keys in captureData are ignored server-side (anti-mass-assignment).
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
    // Persist the FULL capture state (M5): the same flat working-set map + added optional sections
    // the live preview uses, so a saved agreement round-trips its complete content and the stored/
    // signed draft matches the preview. Sent on both create and the CR-B edit PUT.
    captureData: f,
    activeSections: activeSections.value,
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
      savedTrackingNumber.value ?? undefined,
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
// The draft is SCOPED to the chosen (state, type): a draft captured for one template must never
// resume into another. Without this, switching templates (e.g. housing -> commercial) leaks the
// prior template's values -- including shared enum keys whose vocabulary differs (utilitiesBorneBy:
// tenant/owner vs lessee/lessor) -- which the new template's server-side validation rejects (400).
const DRAFT_KEY_PREFIX = "am.preview.draft.v1";
const DRAFT_TTL_MS = 24 * 60 * 60 * 1000;

function draftKey(): string {
  return `${DRAFT_KEY_PREFIX}.${props.state}.${props.type}`;
}

// One-time cleanup of the pre-scoping global draft key: it is unscoped client-side PII at rest that
// the scoped loader never reads, so drop it so it cannot linger past its intent.
function purgeLegacyGlobalDraft(): void {
  try {
    localStorage.removeItem(DRAFT_KEY_PREFIX);
  } catch {
    // ignore
  }
}

function persistDraft(): void {
  try {
    localStorage.setItem(
      draftKey(),
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

// Index the schema's fields by key so a resumed draft value can be validated against the current
// template's field definition (keys are unique across a template).
function buildFieldIndex(s: FormSchema | null): Map<string, FormField> {
  const index = new Map<string, FormField>();
  for (const section of s?.sections ?? []) {
    for (const field of section.fields) index.set(field.key, field);
  }
  return index;
}

function loadDraft(): void {
  try {
    const raw = localStorage.getItem(draftKey());
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
    // Merge only keys that exist in the current (schema-fed) working structure, and only when the
    // stored value is still valid for the current template's field. The enum guard is the important
    // one: a shared key (e.g. utilitiesBorneBy) can carry a value valid for another template but not
    // this one (tenant/owner vs lessee/lessor), which the server would reject -- drop it instead.
    const fieldByKey = buildFieldIndex(schema.value);
    for (const id of Object.keys(working)) {
      const stored = data[id];
      if (!stored) continue;
      for (const key of Object.keys(working[id])) {
        const value = stored[key];
        if (value == null) continue;
        const field = fieldByKey.get(key);
        if (
          field?.widget === "select" &&
          field.options &&
          !field.options.some((o) => o.value === value)
        ) {
          continue; // stored enum value is not an option for this template's field -- skip it.
        }
        working[id][key] = value;
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
    localStorage.removeItem(draftKey());
  } catch {
    // ignore
  }
}

/**
 * Seed the working set from a loaded agreement (edit mode). Restores the FULL capture state (M5): the
 * stored captureData map (dynamic fields + working set) is the base, then the well-known fixed field
 * keys (terms + owner/tenant name/father/address) are overlaid so the authoritative typed columns win
 * (matching the server's D3 reconciliation). The added optional sections are re-activated from the
 * stored activeSections (reconciled against the current schema). An agreement with no stored capture
 * state (a legacy row) restores only the fixed fields + parties, exactly as before.
 */
function prefillFromAgreement(a: AgreementView): void {
  // Fixed typed columns (authoritative) -- these overlay any stored map entry for the same key.
  const fixed: Record<string, string> = {
    propertyAddress: a.propertyAddress,
    address: a.propertyAddress,
    monthlyRent: String(a.monthlyRent),
    rent: String(a.monthlyRent),
    securityDeposit: String(a.securityDeposit),
    deposit: String(a.securityDeposit),
    startDate: a.startDate,
    endDate: a.endDate,
  };
  for (const s of a.signers) {
    const p = s.role.toLowerCase(); // "owner" | "tenant"
    fixed[`${p}Name`] = s.name || `${s.firstName} ${s.lastName}`.trim();
    fixed[`${p}FatherName`] = s.fatherName ?? "";
    fixed[`${p}Address`] = s.currentAddress ?? "";
  }
  // The stored capture map (dynamic values + working set) is the base; the fixed columns win on top.
  const flat: Record<string, string> = { ...(a.captureData ?? {}), ...fixed };
  for (const id of Object.keys(working)) {
    for (const key of Object.keys(working[id])) {
      if (flat[key] != null) working[id][key] = flat[key];
    }
  }
  // Re-activate the stored optional sections, dropping any title the current schema no longer declares
  // as optional (renamed / removed / now mandatory) -- so a stale title can never silently activate.
  if (Array.isArray(a.activeSections)) {
    activeSections.value = reconcileActiveSections(
      a.activeSections,
      schema.value,
    );
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
// The saved agreement's id, held after a create so the Save-to-account (claim) action can target it.
const savedId = ref<string | null>(props.agreementId ?? null);
// Save-to-account (claim) state. Offered only for a freshly-created, not-yet-owned agreement while a
// session is present; editing an already-owned agreement needs no claim.
const claiming = ref(false);
const claimed = ref(false);
const claimError = ref<string | null>(null);
const canSaveToAccount = computed(
  () => !editMode.value && saved.value && !claimed.value && !!auth.session,
);
// The saved agreement's tracking reference (the one persisted number). Held after save so the
// confirmation can show it and the preview/download can render the real number in the provenance
// line (before save it is null -> the server shows its PREVIEW marker instead). In edit mode we seed
// it from the loaded agreement so a retrieved agreement's preview/PDF already shows the real number
// (not the PREVIEW marker) before any re-save.
const savedTrackingNumber = ref<string | null>(
  props.initialAgreement?.trackingNumber ?? null,
);
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
    if (editMode.value && props.agreementId) {
      // Edit: full-replace the owned agreement, then regenerate its draft from the edited terms (the
      // PUT cleared the old pinned draft). The agreement is already owned -- no claim needed.
      const updated = await updateAgreement(
        props.agreementId,
        buildAgreementInput(),
      );
      await generateAgreementDocument(props.agreementId);
      saved.value = true;
      savedId.value = updated.id;
      savedTrackingNumber.value = updated.trackingNumber;
      void refreshPreview();
    } else {
      const created = await createAgreement(buildAgreementInput());
      await generateAgreementDocument(created.id);
      saved.value = true;
      savedId.value = created.id;
      savedTrackingNumber.value = created.trackingNumber; // now the client holds the reference
      // If the user is signed in, claim it straight away so it lands in "My Agreements" without a
      // second click. A failed claim is non-fatal -- the manual "Save to my account" button remains
      // as a fallback (canSaveToAccount stays true while not yet claimed).
      if (auth.session) {
        try {
          await claimAgreement(created.id);
          claimed.value = true;
        } catch {
          claimError.value =
            "Saved, but could not add it to your account. Use Save to my account to retry.";
        }
      }
      void refreshPreview(); // re-render the preview so its provenance line shows the real number
      clearDraft(); // client-side PII cleared once it is safely persisted server-side
    }
  } catch (e) {
    saved.value = false;
    saveError.value =
      e instanceof Error ? e.message : "Could not save. Please try again.";
  } finally {
    saving.value = false;
  }
}

/**
 * Save-to-account: claim the freshly-created agreement into the signed-in identity so it appears in
 * "My Agreements". Idempotent server-side; on success the shell returns to the list.
 */
async function saveToAccount(): Promise<void> {
  if (!savedId.value) return;
  claiming.value = true;
  claimError.value = null;
  try {
    await claimAgreement(savedId.value);
    claimed.value = true;
    emit("saved-to-account");
  } catch {
    claimError.value = "Could not save to your account. Please try again.";
  } finally {
    claiming.value = false;
  }
}

// ---------------------------------------------------------------------------
// Finalise and pay. Finalising places the order and freezes the draft; paying then runs through the
// provider's hosted checkout.
//
// The status shown here is whatever the SERVER reports, never what happened in the checkout window.
// A customer who pays and closes the tab is still marked paid (the webhook settles it), and a
// customer whose window merely closed is never told they paid. "Payment received" appears only on
// PAID; anything else says plainly what is known.
// ---------------------------------------------------------------------------
const paying = ref(false);
const payError = ref<string | null>(null);
const payOutcome = ref<string | null>(null);
const paidAmountLabel = ref<string | null>(null);

/** Whether the pay action is offered: something is saved server-side and it is not already paid. */
const canPay = computed(
  () => saved.value && !!savedId.value && payOutcome.value !== "PAID",
);

const payMessage = computed(() => {
  switch (payOutcome.value) {
    case "PAID":
      return `Payment received${paidAmountLabel.value ? ` (${paidAmountLabel.value})` : ""}. Nothing more to do.`;
    case "PENDING":
      // Deliberately not an error. The webhook is usually a moment behind, and reconciliation is
      // the backstop - telling the customer it failed would be wrong and would invite a second
      // payment.
      return "Payment is being confirmed. This page will show it as soon as your bank confirms - you do not need to pay again.";
    case "DISMISSED":
      return "Payment window closed before payment was completed.";
    case "FAILED":
      return "That payment did not go through. You can try again.";
    default:
      return null;
  }
});

// --- pre-payment contact confirmation -------------------------------------------------------
//
// Contacts are optional while drafting and are not collected by this form at all, so an agreement
// arrives here with none. They are required before money moves: they address the signing
// invitations, they receive the signed agreement, and they are how an anonymous customer gets back
// to this agreement after closing the tab. So "finalise and pay" opens this step first rather than
// going straight to checkout.
//
// The server refuses to create an order for an unreachable agreement regardless of what happens
// here - this screen exists so customers meet that requirement somewhere sensible, not to enforce
// it.

const contactStep = ref(false);
const contactParties = ref<PartyContact[]>([]);
const contactSaving = ref(false);
const contactError = ref<string | null>(null);

// Shown only once the SERVER has confirmed payment (see finaliseAndPay). Never on the strength of
// the checkout handler returning.
const paymentConfirmed = ref(false);

// Whether any party had an address for the recovery link. Drives what the confirmation screen is
// allowed to claim: with nobody contactable it must tell the customer to keep the reference rather
// than promising an email that will never arrive.
const recoveryLinkSent = computed(() =>
  contactParties.value.some((p) => p.email.trim() !== ""),
);

/** Open the contact step, seeded with whatever the server currently holds for each party. */
async function openContactStep(): Promise<void> {
  if (!savedId.value) return;
  contactError.value = null;
  try {
    const agreement = await getAgreement(savedId.value);
    contactParties.value = agreement.signers.map((s) => ({
      id: s.id,
      name: s.name,
      role: s.role,
      email: s.email ?? "",
      mobile: s.mobile ?? "",
    }));
    contactStep.value = true;
  } catch {
    payError.value = "Could not load the party details. Please try again.";
  }
}

/**
 * Whether these contacts differ from what the server last gave us (seeded by openContactStep).
 *
 * Contacts now stay editable until payment settles, so the retry this once rescued is no longer
 * blocked by the server. Skipping the identical save is still worth doing, for two reasons: it
 * keeps a pointless write off a path the customer is trying to get through, and it means a
 * confirm-with-no-edits can never be refused by whatever the freeze happens to be - which is the
 * shape of the bug that stranded a customer here before.
 *
 * The parent's copy is the correct baseline: the step edits a local clone and never writes back
 * into these objects.
 */
function contactsChanged(parties: PartyContact[]): boolean {
  return parties.some((party) => {
    const seeded = contactParties.value.find((p) => p.id === party.id);
    return (
      !seeded ||
      seeded.email.trim() !== party.email.trim() ||
      seeded.mobile.trim() !== party.mobile.trim()
    );
  });
}

/** Save the confirmed contacts, then continue into finalise + payment. */
async function confirmContacts(parties: PartyContact[]): Promise<void> {
  if (!savedId.value) return;
  contactSaving.value = true;
  contactError.value = null;
  try {
    if (contactsChanged(parties)) {
      await updateAgreementContacts(
        savedId.value,
        parties.map((p) => ({
          signerId: p.id,
          email: p.email,
          mobile: p.mobile,
        })),
      );
      // The saved values are the new baseline, so a further retry after another failed payment
      // still sees "nothing changed" and still reaches the pay button.
      contactParties.value = parties.map((p) => ({ ...p }));
    }
    contactStep.value = false;
    // Never throws - it reports its own failures through payError - so a payment problem can never
    // be mislabelled here as a contact problem.
    await finaliseAndPay();
  } catch (e) {
    // The contacts freeze is keyed on PAYMENT, not on the order existing, so this is reachable only
    // once the money is settled. "Please try again" would be a lie there: the freeze is permanent
    // and retrying refuses forever, so the message has to name the real condition instead.
    contactError.value =
      e instanceof AgreementHttpError && e.contactsFrozen
        ? "This agreement is already paid for, so the contact details can no longer be changed here. Contact support if an address is wrong."
        : "Could not save those contact details. Please try again.";
  } finally {
    contactSaving.value = false;
  }
}

async function finaliseAndPay(): Promise<void> {
  if (!savedId.value) return;
  paying.value = true;
  payError.value = null;
  payOutcome.value = null;
  try {
    // Finalise first: it is idempotent, so retrying after a dismissed or failed payment places no
    // second order.
    await finaliseAgreement(savedId.value);
    const outcome = await payForAgreement(savedId.value, {
      name: "AgreementMitra",
      description: "Rental agreement",
    });
    payOutcome.value = outcome;
    if (outcome === "PAID") {
      // Read the amount back from the server rather than echoing anything the checkout window
      // reported - the server is the only place the charged amount is authoritative.
      try {
        const progress = await getPaymentProgress(savedId.value);
        paidAmountLabel.value =
          progress.amountMinorUnits != null && progress.currency
            ? formatMinorUnits(progress.amountMinorUnits, progress.currency)
            : null;
      } catch {
        paidAmountLabel.value = null; // cosmetic only - never downgrade a confirmed payment
      }
      // Only here, inside the PAID branch, which the SERVER decided. The confirmation screen must
      // never be reachable from the checkout handler's return value alone.
      paymentConfirmed.value = true;
    }
  } catch (e) {
    if (e instanceof AgreementHttpError && e.jurisdictionUnsupported) {
      // A retry can never succeed, so do not invite one: say what this agreement CAN still do.
      payError.value =
        "Stamping and eSign are not yet available for this agreement's jurisdiction. You can " +
        "still preview and download the draft free of charge.";
    } else {
      payError.value =
        e instanceof Error && e.message
          ? e.message
          : "Could not start payment. Please try again.";
    }
  } finally {
    paying.value = false;
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
      savedTrackingNumber.value ?? undefined,
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
    // Edit mode prefills the working set from the loaded agreement; a fresh draft resumes the client
    // localStorage draft instead (the two must never mix -- an edit works against server terms only).
    if (editMode.value && props.initialAgreement) {
      prefillFromAgreement(props.initialAgreement);
    } else {
      loadDraft();
    }
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
  purgeLegacyGlobalDraft();
  void loadSchema();
  void fetchEligibleOrNone().then((eligible) => {
    eligibleStates.value = eligible;
  });
});
onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKeydown);
  if (previewTimer) clearTimeout(previewTimer);
});
</script>

<template>
  <!-- The pre-payment contact step. A distinct screen rather than a panel inside the form: it is a
       decision point on the way to paying, and the form behind it must not invite edits while the
       customer is confirming who gets the agreement. Not a route - it needs the agreement this
       flow is already holding, and the path switch in App.vue carries no state across routes. -->
  <ContactConfirmation
    v-if="contactStep"
    :parties="contactParties"
    :saving="contactSaving"
    :error="contactError"
    @confirm="confirmContacts"
    @cancel="contactStep = false"
  />
  <!-- Reached only from the server-confirmed PAID branch. This is the customer's last screen and
       the one place the reference and the recovery link are put in front of them. -->
  <PaymentConfirmation
    v-else-if="paymentConfirmed"
    :reference="savedTrackingNumber ?? ''"
    :amount-label="paidAmountLabel"
    :link-sent="recoveryLinkSent"
    @continue="paymentConfirmed = false"
  />
  <div
    v-else
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
        <span
          v-if="remainingRequired > 0"
          class="whitespace-nowrap text-xs font-medium text-amber-700"
          data-testid="required-remaining"
        >
          Complete {{ remainingRequired }} more required section(s)
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
          {{
            saving ? "Saving..." : editMode ? "Save changes" : "Save & continue"
          }}
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
                <span class="block truncate text-xs italic text-slate-400"
                  >optional</span
                >
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
    <div
      v-if="saved && !saveError"
      class="flex flex-wrap items-center gap-3 border-t border-green-200 bg-green-50 px-4 py-2 text-sm text-green-700"
      data-testid="save-ok"
    >
      <span class="flex-1">
        {{
          editMode
            ? "Changes saved and the draft regenerated - ready for signing."
            : "Agreement saved and its draft generated - ready for signing."
        }}
        Reference
        <span class="font-semibold" data-testid="tracking-number">{{
          savedTrackingNumber
        }}</span
        >.
      </span>
      <!-- Save-to-account (claim): only for a new, not-yet-owned agreement with a live session. -->
      <button
        v-if="canSaveToAccount"
        type="button"
        class="rounded bg-green-700 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
        :disabled="claiming"
        data-testid="save-to-account"
        @click="saveToAccount"
      >
        {{ claiming ? "Saving..." : "Save to my account" }}
      </button>
      <span
        v-else-if="claimed"
        class="text-xs font-semibold"
        data-testid="claimed-ok"
      >
        Saved to My Agreements.
      </span>
      <!-- Finalise and pay. Idempotent server-side, so a retry after a closed or failed payment
           window places no second order and takes no second payment. -->
      <button
        v-if="canPay"
        type="button"
        class="rounded bg-slate-900 px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50"
        :disabled="paying"
        data-testid="finalise-and-pay"
        @click="openContactStep"
      >
        {{ paying ? "Opening payment..." : "Finalise and pay" }}
      </button>
    </div>
    <!-- Payment status. This reflects the SERVER's payment state, never what happened in the
         checkout window: "Payment received" appears only once the server has confirmed it. -->
    <p
      v-if="payMessage"
      class="border-t px-4 py-2 text-sm"
      :class="
        payOutcome === 'PAID'
          ? 'border-green-200 bg-green-50 text-green-700'
          : 'border-amber-200 bg-amber-50 text-amber-800'
      "
      data-testid="pay-status"
    >
      {{ payMessage }}
    </p>
    <p
      v-if="payError"
      class="border-t border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
      data-testid="pay-error"
    >
      {{ payError }}
    </p>
    <p
      v-if="claimError"
      class="border-t border-red-200 bg-red-50 px-4 py-2 text-sm text-red-700"
      data-testid="claim-error"
    >
      {{ claimError }}
    </p>
    <!-- Said here, not only in the picker: a customer can reach this shell directly through a
         recovery link or a resumed draft, and the whole point is that nobody fills in an entire
         agreement before learning it cannot be stamped. -->
    <p
      v-if="isDraftOnlyJurisdiction"
      class="border-t border-amber-200 bg-amber-50 px-4 py-2 text-sm text-amber-800"
      data-testid="draft-only-jurisdiction"
    >
      <strong>Draft and download only.</strong> Stamping and eSign are not yet
      available for this jurisdiction, so this agreement cannot be paid for or
      signed here. You can still preview it and download it free of charge.
    </p>
    <!-- The review screen. This is where the customer is looking at the document they are about to
         commit to, so it is where the notice has to be -- not on the marketing page. -->
    <LegalDisclaimer variant="bar" />
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
        <span class="mr-auto text-xs text-slate-400"
          >escaped & sandboxed - you edit data, never markup</span
        >
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
