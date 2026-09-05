## Context

CR-3a/b/c built: a domain-agnostic renderer, a per-agreement preview (`GET /{id}/preview`, requires a
persisted agreement), and generate-as-draft. This change makes the **preview the interface** and
must therefore render **before** the agreement is complete or persisted. It also sets the
architecture (section registry, stateless preview, HTML+PDF) that the large section catalog in the
proposal depends on. Only Phase 1 is specified/tasked here; later phases reuse the same architecture.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith; Vue 3 + `<script setup>` +
Tailwind; keep `ModularityTests` green; sandbox + dummy data; never log PII; the markup/data security
boundary (users edit data, never template markup).

**Locked reference UI:** the interaction and layout are approved and frozen against
`assets/preview-centric-mockup.html` (a self-contained, interactive mockup -- section modals, add-on
clause catalog, completeness bar, escaping, light/dark, desktop/tablet/phone breakpoints). It is the
visual/flow source of truth for the frontend tasks; see D5. It is a **design reference only** (not a
build target verbatim -- production renders the server HTML variant in a sandboxed iframe, not the
mockup's client-side render).

## Goals / Non-Goals

**Goals (Phase 1):**
- A responsive preview-centric shell: desktop two-pane (sections + live preview), mobile guided
  sections with a one-tap live preview and full-screen section sheets.
- A **stateless partial-preview** endpoint driven by the in-progress client working set -- HTML for
  the instant live pane, PDF for download/final; **nothing persisted**.
- A partial-tolerant template (placeholders for empty sections) so a half-filled agreement renders.
- Section modals for the existing Tenant/Owner/Property data, a completeness status bar, and final
  "Save & continue" wired to the existing create + generate-as-draft.

**Non-Goals (Phase 1):**
- No new persisted fields / schema change; new section *data* persistence is Phase 2+ (attributes
  store). No template catalog / language / clause library (later phases / other CRs). No FSM,
  stamping, or eSign change.

## Decisions

### D1: Live preview = assembled HTML; PDF = fidelity/final (one template, no divergence)

The live pane renders the **same Thymeleaf template** as **HTML** (the assembler's output, pre-
Gotenberg) -- instant, no Chromium round-trip. The **PDF** (Gotenberg) backs "Download PDF" and the
final generated draft. Because both come from the **one** trusted template, there is **no
client-side template and no divergence risk**. **Alternative rejected:** a client-rendered preview
(React/Vue template mirror) -- duplicates the legal template, guaranteeing drift; and PDF-only live
preview -- ~1-2s per edit feels slower than today's form.

**Two caveats the "one template" claim does *not* cover, and how we close them:**

1. **Fonts / complex-script shaping.** The template resolves Noto faces from the *Gotenberg image*
   via fontconfig; a browser has no access to those. An un-embedded HTML pane falls back to system
   fonts and can mis-shape Devanagari/Telugu — exactly the vernacular scripts the preview is meant to
   reassure users about (a stated non-negotiable). So the composed HTML for the live pane **embeds the
   Noto faces as `@font-face` data-URIs** (task 1.3). The PDF path is unchanged.
2. **Mapping, not markup.** "One template" removes the *client* template; it does **not** remove the
   *server-side* DTO->data-map step, which Phase 1 now has in two flavours (persisted-aggregate and
   in-progress-DTO). To keep the promise honest we **single-source the map builder** (see D3) and lock
   it with a parity test (task 4.2). Page geometry/pagination (A4) still differs between the HTML pane
   and the PDF; the pane is explicitly the *fast/approximate* view, the PDF the *fidelity/final* one.

### D2: Stateless preview from an in-progress working set (no premature persistence)

The browser holds the working draft; a new **stateless** endpoint renders **posted** data, returns
the result, and **stores nothing**. `POST /api/agreements/preview` (body = in-progress agreement
data) -> inline **PDF** (`no-store`); the **HTML** variant (content-negotiated or a sibling path)
feeds the live pane. This keeps preview ephemeral (consistent with CR-3b) and defers all persistence
to the deliberate final save (existing create + generate-as-draft). **Alternative rejected:**
persist-a-draft-early + PATCH sections -- premature rows, a draft lifecycle, and PII pinned for every
keystroke; and reusing `GET /{id}/preview` -- it requires a persisted, fully-valid agreement.

### D3: Renderer tolerates partial data; template shows placeholders

The DTO->data-map mapper accepts **missing/blank** fields and empty party lists; the template uses
`th:if`/placeholders (e.g. "Owner: [ add owner details ]") so an incomplete agreement still renders a
coherent document. Placeholders cover **scalar** fields too (dates, money, `agreementDate`), never a
bare `null`. This is what makes "preview is the starting point" possible -- the empty agreement
renders as a template outline that fills in. **Alternative rejected:** requiring full validity to
preview -- defeats the whole model.

**Server-derived fields.** Today the aggregate owns three derivations the template consumes:
`durationMonths` (from start/end dates), `agreementDate` (from `createdAt`), and each party's display
`name` (first+last fallback). A stateless preview has **no aggregate**, so the mapper must reproduce
them: derive `durationMonths` **only when both dates are present** (placeholder otherwise), pick a
defined preview value for `agreementDate` (no `createdAt` yet — today's date on the render host, or a
"[ date on signing ]" placeholder), and apply the same name fallback. To avoid drift, prefer routing
the DTO through the **existing `AgreementDocumentMapper`** via a transient, non-persisted aggregate —
**but verify `Agreement` construction tolerates partial data**; if its invariants reject a half-filled
draft, extract one shared map-builder both paths call instead. Either way a **single source** owns the
derivations, and task 4.2's parity test enforces it.

### D4: Pluggable section registry (frontend + data contract)

Each section is declared once as `{id, label, completeness rule, modal component, working-data
slice, template binding}`. The shell iterates the registry to render the status bar and buttons; a
modal edits its slice; saving updates the working set and refreshes the preview. Adding a section
(the whole catalog) is then a registry entry + a template binding -- not a shell rewrite. **This is
the architectural payoff** that makes the section catalog tractable.

### D5: Responsive layout (India is mobile-first) -- LOCKED against the reference UI

The interaction + layout are **locked** to the approved reference mockup at
`assets/preview-centric-mockup.html` (open in a browser; fully interactive -- section modals,
completeness bar, add-on clause injection, escaping, light/dark, all three breakpoints). Phase-1 UI
tasks (3.3--3.6) implement **that** shell; it is the source of truth for spacing, states, and flow.

Breakpoints (as built in the reference UI):

| Width | Layout |
| --- | --- |
| **>= 900px (desktop)** | Two panes -- section rail left, sticky live document right. |
| **< 900px (tablet)** | One column; guided **section list is primary**, with a top-bar **Sections <-> Preview toggle** to a full-bleed document. |
| **< 640px (phone)** | Section editors open as **full-screen sheets sliding up from the bottom**; modal form drops to **one column**; document padding shrinks; the top bar tightens (drops the subtitle, keeps the completeness meter full-width). |

Section modals keep **focus-trap + Esc** at every width. **The document-first benefit on desktop;
guided simplicity on phones. Alternative rejected:** a desktop PDF canvas forced onto phones -- worse
than today.

**Mobile-specific manual checks (owed at implementation, beyond the mockup):** the live pane is a
server-HTML sandboxed iframe, so on a real phone verify (a) the **embedded-Noto font payload** (task
1.3) is acceptable on a 3G/4G connection, and (b) **iframe scroll/zoom on iOS Safari** (sandboxed
document content sometimes needs `-webkit-overflow-scrolling` tuning). Recorded in task 7.3.

### D6: Security -- escaped data in a sandboxed iframe; boundary preserved

Working data is HTML-escaped by the same Thymeleaf template; the live-HTML pane is embedded in a
**sandboxed iframe** (no scripts; the template has none) so escaped data can never execute. Users
edit **data**, never markup. The stateless preview is `no-store`, persists nothing, and logs nothing
(neither bytes/HTML **nor validation-error field values**); the HTML variant also carries a
`Content-Security-Policy` and the existing `nosniff` default.

**Two surfaces the "persists nothing / logs nothing" line does not by itself cover:**

- **Client-side PII at rest.** If localStorage backs refresh-resume (D2 open question), the full
  working set — names, father's names, addresses — sits unencrypted in the browser, a real exposure on
  shared/kiosk devices (mobile-first India). "Persists nothing" is a **server-side** statement.
  Mitigation: **clear on successful Save & continue and on explicit abandon/reset**, plus a short TTL
  on stale drafts (tasks 3.1, 6.3). The proposal PII checklist is updated to say so.
- **Unauthenticated render abuse.** `POST .../preview` renders straight from an anonymous body with
  **no persisted-agreement precondition** (unlike `GET /{id}/preview`) — the cheapest render to abuse.
  Two controls: the in-progress DTO **keeps its size/length bounds** (only required-ness is relaxed),
  capping any single payload; and **rate-limiting is an owed companion before this leaves sandbox**,
  recorded with the other deferred ownership/rate-limit work.

## Data / state model (Phase 1)

- **Working set** (client reactive store): today's captured fields (parties, property, money, dates).
  Later phases extend the slice per new section; the store shape is section-keyed from the start.
- **Preview call:** debounced on section-save (not per keystroke) -> `POST .../preview` -> HTML pane
  (and PDF on demand).
- **Persistence:** only on "Save & continue" -> existing `POST /agreements` then
  `POST /{id}/document`. Phase 1 persists the existing fields; new-section persistence is Phase 2+
  (a flexible agreement-attributes store, so it is not a column-per-knob explosion).

## Risks / Trade-offs

- **Frontend size** -- the shell is a large rework; mitigated by the section registry (sections are
  additive) and by keeping the existing endpoints for persistence.
- **Partial-preview correctness** -- placeholders must read well at every fill stage; covered by
  render tests over empty/partial data.
- **Mobile modal ergonomics** -- full-screen sheets + focus management; a real design cost, called
  out for review.
- **Preview latency** -- solved by the HTML pane (instant); PDF only on download/final.

## Migration Plan

**No database migration in Phase 1.** New endpoint(s), template placeholders, and the frontend shell.
The current sequential form is kept working until the new shell is validated (ship behind the same
route, swap when ready). Later phases add the attributes store (its own migration) as sections gain
persistence.

## Resolved (were open questions)

- **HTML vs PDF delivery** -- **decided: content-negotiation** on one `POST .../preview` (`Accept:
  text/html` vs `application/pdf`, PDF default), per task 2.3. Sibling paths rejected as redundant.
- **Working-set persistence for resume** -- **decided: localStorage for Phase 1** (server-side on
  login later, ties to `mobile-otp-auth`), **conditioned on** the clear-on-save / clear-on-reset / TTL
  handling in D6 so the client-at-rest PII exposure is bounded.

## Open Questions (for review)
- **Click-to-edit** -- clicking a clause region in the HTML pane to open its modal (stretch, D-catalog
  E in proposal) -- Phase 3.
