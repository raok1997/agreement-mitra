## Why

The `template-document-projection` compiler draws every rental agreement -- both the live preview and
the signed PDF -- from **one** system-owned `TemplateCompiler`. Today that compiler emits a flat,
generic layout: a bare `<h2>` per section, a single label/value table shape for every section, an
ordered clause list, and a sans-serif body with zero page margins and no document header. That does
not read like a real rental agreement, and it does not match the reference artifact
(`claude.ai/code/artifact/fd23f28f-cdcb-4883-83ca-ac966285c12a`) the team agreed is the target
(flow-journal, locked decision 2).

This change is **module M1** of the `agreement-document-format` umbrella: rework the one system-owned
compiler so it emits the reference-artifact document layout -- a centred header, section bodies drawn
in the style each section declares, real page margins, and a serif Latin body that keeps the bundled
Noto faces for Indic shaping -- for **both** render tiers (preview + PDF) and **both** jurisdictions
(National `IN` + Telangana `TG`). It depends on **M0 (`template-document-metadata`)** for the two
declarative inputs it consumes: `meta.document {title, subtitle, executionLine}` and each section's
`render` kind (`parties | keyvalue | clauses | annexure`). The layout style is learned from the
template, never hardcoded per jurisdiction.

The move is deliberately confined to **how the document is drawn**. The markup/data escaping boundary,
the sandboxed `showWhen` DSL, preview<->PDF parity, statelessness, and the never-log rule are all
preserved. The compiler stays a **pure function of its inputs**: the execution date (which falls back
to the current system date when the agreement date is blank) is resolved to a concrete value at the
projection layer via an injected `Clock` and passed into `compile`, so `compile` never reads a clock.

## What Changes

- **Centred document header from `meta.document`.** The compiler emits a centred header at the top of
  the document built from the effective template's `meta.document`: the `title`, the `subtitle`, and
  an **execution line** whose authored text carries `{{slot}}` fills (e.g. `{{agreementDate}}`). The
  title, subtitle, and execution-line text are **all HTML-escaped** as literal text (they are
  system-authored template text, escaped exactly like clause text is today), and every `{{slot}}` in
  the execution line is filled with its **HTML-escaped** value.
- **Section bodies dispatched by the section `render` kind.** Instead of one grouping heuristic for
  every section, the compiler dispatches on the M0-declared `render` kind:
  - `parties` -> a **party card** (a label/value block), used for the separate Owner and Tenant
    sections;
  - `keyvalue` -> the **current label/value table** (used for Property, Term, and the Financial
    "Terms of Tenancy" table);
  - `clauses` -> a **numbered ordered list** (the "Now This Agreement Witnesseth" list);
  - `annexure` -> a **bulleted annexure list**.
  Every field label, every field value, and every clause text stays HTML-escaped; `showWhen` still
  gates each clause through the sandboxed DSL only.
- **Serif Latin body + kept Noto faces + real page margins.** The body font becomes a **serif** Latin
  family while the bundled Noto faces (embedded as data-URIs by `DocumentFonts`) are **kept** so
  Devanagari/Telugu and other Indic scripts still shape correctly (the Chromium/Noto gotcha). The
  document gains **real page margins** on **both** preview and PDF via CSS `@page` plus body padding,
  so the live pane and the PDF frame the content identically.
- **Execution date resolved at the projection layer, compiler stays pure.** `DocumentProjectionService`
  gains an injected `java.time.Clock`. At render time it resolves the execution date to a concrete
  value: the submitted **`agreementDate`** field value when present and non-blank, else **the current
  system date (SYSDATE)** read from the injected clock. That concrete date value is **passed into**
  `compile`, which binds it under a **reserved date-binding key** (`agreementDate`) so the header
  execution line (and any clause slot) fills from it. `compile` therefore never reads a clock and
  stays a pure function of `(effective template, data, resolved execution date)`.

**Preserved (non-negotiable, unchanged by this CR):**

- **Preview<->PDF parity** -- the live-pane HTML is byte-for-byte the HTML source the PDF is rendered
  from, because one compiler produces both from the same inputs (including the same resolved execution
  date).
- **The markup/data escaping boundary** -- every data value **and** every piece of system-authored
  template text (title, subtitle, execution-line text, field labels, clause text) is HTML-escaped;
  document structure remains system-owned markup a user value can never become.
- **The sandboxed `showWhen` DSL** -- clause inclusion is still decided solely by the resolution
  engine's hand-written boolean DSL, never Thymeleaf/SpringEL/any expression engine.
- **The never-log rule** -- neither the composed HTML nor any submitted value is logged.
- **The signature block** stays at the foot of the document.

**Explicitly not in this change** (named follow-ons): declaring `meta.document` / `render` / `optional`
in the schema and records (that is **M0, `template-document-metadata`**, a prerequisite);
opt-in/optional-section gating via `activeSections` (**M2, `optional-section-opt-in`**); form-schema
section semantics (**M3**); the capture UX (**M4**); and authoring the production `sets/rental/`
content -- the Owner/Tenant split, the `meta.document` wording, and per-section render tags (**M5,
`rental-document-content-v2`**). This CR reworks the **compiler's rendering**; M5 supplies the content
that exercises it.

## Capabilities (Modified: template-document-projection)

This change **modifies** the `template-document-projection` capability. It reworks the
`TemplateCompiler`'s output and threads a resolved execution date through `DocumentProjectionService`;
it adds no new capability and no new module.

- **MODIFIED -- Compile an effective template plus user data into escaped HTML.** The compiler now
  emits the reference-artifact layout: a centred header from `meta.document` and section bodies
  dispatched by each section's `render` kind (`parties` -> party card, `keyvalue` -> label/value
  table, `clauses` -> numbered list, `annexure` -> bulleted list). It also takes a **resolved
  execution date** as an input and binds it under the reserved `agreementDate` key. Every data value
  **and** every system-authored template text (title, subtitle, execution-line text, labels, clause
  text) stays HTML-escaped; `showWhen` still runs only through the sandboxed DSL; placeholders and
  self-containment are unchanged.
- **MODIFIED -- The live preview and the signed PDF come from one compiler (parity).** Parity is
  preserved across the new layout: preview and PDF are produced from the one compiler with the same
  inputs (including the same resolved execution date), for both `IN` and `TG`, with page margins on
  both tiers, so the previewed document is byte-for-byte the signed document's HTML source.
- **MODIFIED -- Rendering embeds party PII safely -- escaped, offline, and never logged.** The new
  header, party cards, annexure, and clause list keep the escape-everything boundary (all template
  text and all data escaped), stay offline/self-contained (fonts embedded as data-URIs, no external
  URL), and never log the composed HTML or a submitted value.
- **MODIFIED -- Serve a stateless document preview that persists nothing.** The stateless preview now
  resolves the execution date at the projection layer (submitted `agreementDate` else the injected
  clock's date) and passes it into compile; it still returns escaped HTML (or a Gotenberg PDF),
  `Cache-Control: no-store`, persists nothing, and renders placeholders for absent fields. The
  rendered document carries the artifact header and page margins.

## Impact

- **`documents` module (`documents.template`, package-private).** `TemplateCompiler` is reworked: a
  header emitter reading `meta.document`, a render-kind dispatch replacing the single grouping
  heuristic (party card / key-value table / numbered clause list / bulleted annexure), a serif body +
  `@page`/padding margins in the self-contained stylesheet (the Noto `@font-face` data-URIs from
  `DocumentFonts` are kept unchanged), and a new `compile` input carrying the resolved execution date
  bound under the reserved `agreementDate` key. `DocumentProjectionService` gains an injected
  `java.time.Clock`, resolves the execution date once per render, and passes it down the single
  compile path (so preview and PDF share it). No public `documents.api` type changes -- `compile` is
  package-private; `DocumentProjectionRequest` is untouched here (`activeSections` is M2's).
- **Determinism trade-off (accepted, locked decision 4).** When `agreementDate` is blank the header's
  execution date is the current system date, so the rendered header **changes across calendar days**
  for the same data. The render stays reproducible given `(template, data, resolved execution date)`;
  the injected `Clock` makes the fallback fully testable (a fixed clock yields a fixed header). The
  reserved date-binding key (`agreementDate`) is documented so M5 can author the execution line and so
  the fallback is not a hidden behavior.
- **`ModularityTests` stays green.** No new module, no widened record: the compiler, the projection
  service, and the resolved-date plumbing stay package-private in `documents.template`; the only public
  surface (`documents.api`) is unchanged by this CR.
- **Dependencies:** **none added.** `java.time.Clock`, `HtmlUtils`, and the existing Gotenberg/Noto
  wiring are already present. **No `gradle.lockfile` change**; nothing new enters the OSV
  `securityScan` surface.
- **Depends on:** **M0 (`template-document-metadata`)** for `Meta.document`, `Section.optional`, and
  `Section.renderKind` on the effective template. Apply M0 first. Independent of M2/M3/M4/M5 (it
  renders the layout; M5 supplies the content that fills it).
- **No change to:** the resolver, its precedence, effective-template identity/hash, the
  `SubmittedDataValidator`, the Gotenberg `HtmlPdfRenderer` seam, the render tiers
  (preview/generate/download), statelessness, the signing FSM, the webhook/eSign flow, stamping, or
  object storage.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No new PII sink.** The compiler still
  embeds the same submitted party PII (names, rent, dates) it already embeds -- this CR only changes
  **how** that data is laid out (header, party cards, tables, lists) and keeps every value
  **HTML-escaped** at compile time. No Aadhaar number, OTP, virtual id, or secret is introduced,
  logged, or persisted. The resolved execution date is a system-derived calendar date (or a submitted
  date field), escaped like any other value.
- **Markup/data boundary held -- and widened to all template text.** Document structure stays
  system-owned markup. Every data value **and** every system-authored template text -- the new header
  `title`, `subtitle`, and execution-line text, plus field labels and clause text -- is HTML-escaped,
  so neither a user value nor an authored string can become active content or structure. A value that
  looks like `{{slot}}` is never re-substituted. Injection-as-data renders inert (a dedicated test).
- **`showWhen` stays the sandboxed DSL.** Clause inclusion in the new numbered list is still decided
  solely by the resolution engine's hand-written boolean DSL over declared fields and literals --
  never Thymeleaf, SpringEL, or any expression engine. No code-execution surface is added.
- **Offline / self-contained render preserved.** The stylesheet references fonts by family name and
  embeds the Noto faces as data-URIs; the new serif body and `@page` margins add **no** `url(...)` and
  no external link, so Gotenberg's denied outbound network is never exercised by a render.
- **Never-log preserved.** Neither the composed HTML (now including the header) nor any submitted value
  is written to any log at any level. Validation errors still cite field keys / rule tokens only.
- **Parity preserved.** One compiler still produces both the preview HTML and the PDF's HTML source
  from the same inputs (including the same resolved execution date), so the previewed document is
  byte-for-byte the signed document -- no divergent renderer is introduced.
- **Statelessness preserved.** The stateless preview still persists nothing and is served
  `Cache-Control: no-store`; resolving the execution date reads a clock but writes no state.
- **Sandbox + dummy data only?** Preserved -- no live provider, credential, or env var is added; the
  layout is exercised with the reference/dummy `IN` and `TG` sets.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
