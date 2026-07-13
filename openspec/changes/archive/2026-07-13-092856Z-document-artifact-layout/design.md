## Context

`template-document-projection` delivers one system-owned `TemplateCompiler` that maps an
`EffectiveTemplate` + a submitted data map into a self-contained HTML document, and one
`DocumentProjectionService` that resolves `(state, type)`, validates the data, compiles **once**, and
returns either that HTML or the identical HTML handed to the Gotenberg `HtmlPdfRenderer` seam
(byte-for-byte parity). The compiler's current output is generic: `<!DOCTYPE>` + an embedded
stylesheet, then `<section><h2>title</h2>` per section, a single grouping heuristic that packs
consecutive field entries into a `table.kv` and consecutive included clauses into `ol.clauses`, then a
fixed signature block. The body is sans-serif (`"Noto Sans", ...`) with `margin: 0` -- no header, no
page margins, no per-section layout variation.

This CR (`agreement-document-format` module **M1**) reworks that compiler to emit the reference
artifact's layout, for both preview + PDF and both `IN` + `TG`, without disturbing the safety
invariants. It consumes two declarative inputs that module **M0 (`template-document-metadata`)** adds
to the effective template: `Meta.document {title, subtitle, executionLine}` and `Section.renderKind`
(`parties | keyvalue | clauses | annexure`, default `keyvalue`). M0 is a hard prerequisite.

Constraints unchanged: Java 21 + Spring Boot 3.5.x modular monolith; records for value objects;
constructor injection; package-private by default (`public` only on `documents.api`); keep
`ModularityTests` green; the markup/data boundary (escape every data value and every template text);
`showWhen` stays the sandboxed DSL; the render stays offline/self-contained and never-logged; preview
== PDF.

## Goals / Non-Goals

**Goals**

- Emit a **centred header** from `meta.document`: escaped `title`, escaped `subtitle`, and an escaped
  **execution line** whose `{{slot}}` fills are HTML-escaped.
- **Dispatch section bodies by `render` kind**: `parties` -> party card; `keyvalue` -> the current
  label/value table; `clauses` -> a numbered ordered list; `annexure` -> a bulleted list. Keep every
  label / value / clause text HTML-escaped and `showWhen` gating each clause.
- **Serif Latin body**, keeping the bundled Noto data-URI faces for Indic shaping; **real page
  margins** on preview + PDF via CSS `@page` + body padding.
- **Resolve the execution date** at `DocumentProjectionService` from the submitted `agreementDate`
  (when present + non-blank) else the injected `Clock`'s date, and **pass the concrete value into
  `compile`** so the compiler stays pure. Bind it under a **reserved date-binding key**.
- Preserve preview<->PDF parity, the escaping boundary, the sandboxed `showWhen`, statelessness, the
  never-log rule, and the signature block.
- Unit-test each render kind + header + execution-date resolution + injection-inert + margins;
  integration-test resolve+compile for `IN` and `TG` and compile->Gotenberg parity.

**Non-Goals (separate CRs)**

- **Declaring `meta.document`, `render`, and `optional` in the schema / loader / validator / canonical
  hash** -- that is **M0 (`template-document-metadata`)**, the prerequisite. This CR only **consumes**
  those fields off the effective template.
- **Optional-section opt-in (`activeSections`)** -- **M2**. This CR renders every section the effective
  template carries; it does not gate optional sections.
- **Form-schema section semantics** (**M3**), **capture UX** (**M4**), and **authoring the production
  `sets/rental/` content** -- the Owner/Tenant split, the header wording, per-section render tags, the
  Telangana optional catalog (**M5**). M5 supplies content that this compiler renders.
- Changing the resolver, effective-template identity/hash, the validator, the Gotenberg seam, or the
  render tiers.

## Decisions

### D1: Header emitted from `meta.document`; all header text escaped

The compiler emits a single centred header block ahead of the sections, built from the effective
template's `meta.document`: `<div class="doc-header">` with the `title`, the `subtitle`, and the
**execution line**. All three are **system-authored template text** and are HTML-escaped exactly as
clause text is today. The execution line's authored text may contain `{{slot}}` placeholders (e.g.
`Executed on this {{agreementDate}} at {{place}}`); each slot is filled with its value via the
**existing** slot-fill path (`SLOT` regex + `valueOrPlaceholder`), and both the literal segments and
each substituted value are escaped -- identical discipline to `renderClauseText`, so a header slot is
never an injection vector and a missing slot renders an escaped `[ label ]` placeholder.

**Rationale:** the header is content, declared in the template (locked decision 3), not hardcoded per
jurisdiction -- `IN` and `TG` differ only by their `meta.document` text. Reusing the clause slot-fill
path keeps one escaping code path.

**Alternative rejected:** treating header text as trusted/pre-escaped markup -- it would open a
structure-injection hole and split the escaping boundary; every template string stays escaped.

### D2: Section body dispatched by `Section.renderKind`, not by an entry-shape heuristic

The current `appendSectionBody` infers layout from entry types (fields -> table, clauses -> list). This
CR replaces that inference with an explicit **dispatch on `section.renderKind()`** (from M0):

- `parties` -> a **party card**: a label/value block (`<div class="party-card">` with label/value
  rows) rendering the section's field entries -- used for the separate Owner and Tenant sections.
- `keyvalue` -> the **current label/value table** (`table.kv`) -- Property, Term, and the Financial
  "Terms of Tenancy" table. This is the existing table rendering, retained.
- `clauses` -> a **numbered ordered list** (`ol.clauses`) of the section's included clauses -- the
  "Now This Agreement Witnesseth" list. Each clause is still gated by `showWhen` and dropped clauses
  close up the numbering.
- `annexure` -> a **bulleted list** (`ul.annexure`) of the section's entries.

Field labels, field values, and clause text stay HTML-escaped in every kind. An unknown/unsupported
render kind is not expected (M0's validator rejects unknown kinds at load); defensively the compiler
falls back to `keyvalue` so a render never throws. Each section still renders its escaped `title` as a
heading above the dispatched body (the header block is separate, at document top).

**Rationale:** the artifact shows genuinely different section shapes (party cards vs terms table vs
witnesseth list vs annexure); the shape is a template-declared property, so the compiler learns it from
`renderKind` rather than guessing from entry composition (locked decision 2).

**Alternative rejected:** keeping the entry-shape heuristic and adding CSS classes -- it cannot
distinguish a party card from a generic key/value table (both are field entries), and it hardcodes
layout intent the template should own.

### D3: Execution date resolved at the projection layer via an injected `Clock`; compiler stays pure

`compile` must not read a clock -- a pure function is testable and parity-safe. So:

- `DocumentProjectionService` gains a constructor-injected `java.time.Clock` (a `@Bean` provides
  `Clock.systemDefaultZone()` in the app; tests inject `Clock.fixed(...)`).
- On the single compile path, the service **resolves the execution date once**: read the submitted
  `agreementDate` from the data map; if present and non-blank, use it; else read `LocalDate.now(clock)`
  (the current system date, SYSDATE). The resolved value is a concrete date (a `LocalDate`, or its
  formatted string) with **no** clock read left in the compiler.
- The resolved value is **passed into `compile`** as a new parameter and bound under the **reserved
  date-binding key** (D4). Because `previewHtml`, `previewPdf`, and `generate` all funnel through the
  one compile path, they all resolve and pass the **same** execution date -- preserving parity (a
  preview and its PDF share the identical resolved date).

**Rationale:** locked decision 4 -- keep the compiler pure and make the SYSDATE fallback injectable so
it is deterministic in tests. **Determinism trade-off (accepted):** with a blank `agreementDate` the
header changes across calendar days; the render is reproducible given `(template, data, resolved
date)`, and a fixed test clock pins it.

**Alternative rejected:** reading `LocalDate.now()` inside the compiler -- it makes the compiler
impure, hides a clock read behind a "pure" signature, and is awkward to test deterministically.

### D4: The reserved date-binding key (`agreementDate`)

The resolved execution date is bound into the values map the compiler renders from under the reserved
key **`agreementDate`**, so the header execution line authored as `... {{agreementDate}} ...` (and any
clause slot referencing `agreementDate`) fills from the **resolved** value -- never blank, never a
raw clock read in the compiler. The binding is applied to a copy of the data map inside `compile`
(the request's data is not mutated) with the resolved value **overriding** any submitted `agreementDate`
so the header always shows the resolved date (submitted-when-present, SYSDATE otherwise). The key is
**documented** (here + in `tasks.md`) so M5 authors the execution line against it and the fallback is
not hidden behavior. The value is escaped like any slot value.

**Rationale:** the frozen contract (flow-journal section 4) names `executionLine` with `{{slot}}` fills
"e.g. `{{agreementDate}}`" -- reusing that key means M5's authored line "just works" and the resolution
lives in one documented place.

**Alternative rejected:** a distinct sentinel key (e.g. `__executionDate`) -- it would force M5 to
author against an internal name and duplicate the concept the `agreementDate` field already expresses.

### D5: Serif Latin body, kept Noto faces, real page margins on both tiers

The self-contained stylesheet changes: the body `font-family` becomes a **serif** Latin stack while the
Indic families stay in the stack (e.g. `"<Serif>", "Noto Sans Devanagari", "Noto Serif Telugu", serif`)
so Latin renders serif and Indic scripts still shape via the **kept** Noto `@font-face` data-URIs that
`DocumentFonts` embeds (unchanged -- a missing face still degrades to family-name, never fails). Page
margins arrive via **CSS `@page { margin: ... }` plus body padding**, applied in the one stylesheet so
they hold on **both** the browser live pane (padding) and the Gotenberg PDF (`@page`) -- the pane and
the PDF frame the content the same way. No `url(...)` beyond the existing data-URI faces is added, so
the offline/self-contained guarantee is intact.

**Rationale:** the artifact reads as a formal serif legal document with real margins; keeping Noto is
the Chromium/Indic-shaping gotcha from `CLAUDE.md`. Both tiers must match for parity, so margins live
in the shared stylesheet, not a tier-specific wrapper.

**Alternative rejected:** dropping the Noto faces for a pure serif stack -- it breaks Devanagari/Telugu
shaping (the documented gotcha); adding margins only to the PDF via a Gotenberg option -- it would
diverge the pane from the PDF and break parity.

### D6: `compile` signature and the single compile path

`compile` gains the resolved execution date input: `compile(EffectiveTemplate, Map<String,Object> data,
<resolved date>)` (package-private, internal to `documents.template`). The public `documents.api`
surface is **unchanged** -- `DocumentProjectionRequest` is not touched here (`activeSections` is M2's).
`DocumentProjectionService` keeps its one private compile path so preview HTML, preview PDF, and
generate all share the same layout and the same resolved date -- parity by construction.

**Rationale:** threading the date as a value keeps the compiler pure (D3) without leaking a new type
across the module boundary.

## Layout sketch (ASCII, structure only -- all text/data escaped)

```
  <body>  (serif Latin body; "Noto ..." kept in stack for Indic; @page + padding margins)
    <div class="doc-header">              <- from meta.document (D1)
       <h1 class="doc-title">   {escaped title}
       <div class="doc-subtitle"> {escaped subtitle}
       <p class="doc-exec">      {escaped executionLine with {{agreementDate}} -> resolved date}
    <section>  <h2>{escaped section title}</h2>
       body dispatched by section.renderKind (D2):
         parties   -> <div class="party-card"> label/value rows        (Owner, Tenant)
         keyvalue  -> <table class="kv"> label/value rows              (Property, Term, Financial)
         clauses   -> <ol class="clauses"> numbered, showWhen-gated    (Now This Agreement Witnesseth)
         annexure  -> <ul class="annexure"> bulleted entries
    ... one <section> per template section ...
    <section class="signatures"> ... signature block (unchanged) ...
```

## Risks / Trade-offs

- **Execution-date determinism.** Blank `agreementDate` -> the header's date is SYSDATE, so the render
  changes across calendar days for the same data (accepted, locked decision 4). Mitigation: the
  injected `Clock` makes it deterministic in tests (a fixed clock pins the header), the render is
  reproducible given `(template, data, resolved date)`, and the reserved key is documented.
- **Depends on M0's shape.** This CR consumes `meta.document` and `Section.renderKind`; if M0's field
  names/shape shift, the compiler must follow. Mitigation: M0 is the frozen-contract owner (flow-journal
  section 4); apply M0 first and consume exactly its shape.
- **Parity regressions from the richer layout.** More markup (header, party cards, `@page`) is more
  surface to diverge preview from PDF. Mitigation: one compiler, one compile path, one shared
  stylesheet, one resolved date -- and an integration test asserting the preview HTML equals the PDF's
  HTML source over both `IN` and `TG`.
- **Indic shaping after the serif switch.** A serif Latin stack must not evict the Noto Indic families.
  Mitigation: keep the Noto families in the `font-family` stack and keep `DocumentFonts`' data-URI
  faces; the missing-face degradation is unchanged.
- **Unknown render kind at compile.** If a section reaches the compiler with an unrecognized kind (M0's
  validator should prevent it), the compiler falls back to `keyvalue` rather than throwing, so a render
  never fails on layout.

## Migration Plan

Additive to `template-document-projection`; **depends on M0 (`template-document-metadata`)**. Steps:

1. Rework `TemplateCompiler`: emit the `meta.document` header (D1); dispatch section bodies on
   `renderKind` (D2, party card / table / numbered clauses / annexure); serif body + kept Noto faces +
   `@page`/padding margins in the stylesheet (D5); add the resolved-execution-date `compile` input
   bound under the reserved `agreementDate` key (D4). Keep escaping, `showWhen`, self-containment, and
   the signature block.
2. Inject `java.time.Clock` into `DocumentProjectionService` (a `@Bean` supplies
   `Clock.systemDefaultZone()`); resolve the execution date once on the single compile path and pass it
   into `compile` (D3), so preview + PDF + generate share it.
3. Tests: unit (each render kind's escaped structure; header from `meta.document`; execution date =
   `agreementDate` when present, injected-clock date when blank; injection-as-data inert; margins
   present) and integration (resolve+compile `IN` and `TG` -> artifact layout; compile->Gotenberg PDF
   renders and the preview HTML equals the PDF's HTML source -- parity). Keep `ModularityTests` green.

No schema/migration change (M0 owns the schema); no dependency/lockfile change. Rollback in this
pre-production sandbox is reverting the compiler + service change; nothing is persisted.

## Open Questions

- **Execution-date format.** The resolved date is rendered as a fixed, locale-appropriate string (e.g.
  `12 July 2026`). Proposing a single deterministic format applied at resolution so the header is
  stable given a date; confirm the exact format with M5's header wording.
- **Annexure entry source.** `annexure` renders a bulleted list of the section's entries; whether an
  annexure item is a field label, a field value, or a clause is an M5 content decision. Proposing the
  compiler renders each entry's escaped label/text uniformly; confirm against M5's fixtures.
- **Party-card field ordering.** The party card renders the section's field entries in authored order
  (same as the table). Confirm the Owner/Tenant sections read correctly for both `IN` and `TG` once M5
  authors them (flow-journal section 6, document-only-vs-capture note).
