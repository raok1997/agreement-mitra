## Context

The templating stack's document face is `template-document-projection`: a package-private
`TemplateCompiler` fills HTML-escaped `{{slots}}` and fires the sandboxed `showWhen` DSL to
include/drop clauses; a package-private `DocumentProjectionService` implements the public
`DocumentProjectionApi` (preview + generate tiers) and is the single compile path that guarantees the
live-pane HTML is byte-for-byte the PDF's HTML source (parity, non-negotiable). The public request
shape is `DocumentProjectionRequest { dimensions?, data }` on the `documents.api` named interface.

The `agreement-document-format` umbrella (`flow-journal.md`) decomposes the artifact-match work into
modules. **M0 (`template-document-metadata`)** already made a section's `optional` flag a declared,
canonicalized, hashed template fact (`Section.optional`, default `false`). **M1
(`document-artifact-layout`)** already reworked the compiler to emit the full artifact layout, drawing
every section by its render kind. This CR is **M2**: make optional sections **opt-in** at the
projection layer. It consumes M0's `optional` flag and M1's compiler; it depends on both.

Frozen contract (flow-journal section 4): `DocumentProjectionRequest { dimensions?{state,type}, data:
Map<key,value>, activeSections: string[] }` -- `activeSections` = added optional section titles;
mandatory sections always render; unknown titles ignored.

Constraints unchanged: Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default, `public` only on the module `api`; keep `ModularityTests`
green; sandbox + dummy data only; the markup/data boundary (template structure is system-owned; users
pick and fill, never author markup); single-compiler parity; never-log.

## Goals / Non-Goals

**Goals**

- Add `activeSections` (`List<String>`) to the public `DocumentProjectionRequest` -- additive,
  backward-compatible, order-preserving, null-tolerant.
- Gate section rendering in the one compiler: a section renders **iff** it is mandatory
  (`section.optional == false`) **or** its `title` is in `activeSections`.
- An un-added optional section contributes **nothing** (no header, fields, clauses, or markup) to
  preview or PDF. An unknown/typo title in `activeSections` is **ignored** (renders nothing, no
  error).
- Define **both faces**: PREVIEW takes `activeSections` from the request; GENERATE renders mandatory
  plus any explicitly given active-set (default none).
- Preserve escaping, the sandboxed `showWhen` DSL, statelessness, single-compiler parity within a
  face, and never-log.

**Non-Goals (each a separate concern / named follow-on CR)**

- **The capture UX** -- marking sections mandatory/optional, the add-optional catalog that populates
  `activeSections`, and hard-blocking Save until mandatory sections are complete. That is **M4**
  (`capture-mandatory-optional-ux`). This CR only freezes the `activeSections` field M4 sends.
- **Recording the active-set on the persisted agreement** so generate-as-draft and the signed draft
  honour the same optional sections a preview added. This is the **named follow-on** that closes the
  parity caveat (D3). Out of scope here.
- **The `optional` flag itself** -- M0. **The compiler layout** -- M1. Both already applied; this CR
  neither redeclares nor re-lays-out.

## Decisions

### D1: `activeSections` is an additive field on the public `DocumentProjectionRequest`

`DocumentProjectionRequest` becomes `record DocumentProjectionRequest(DocumentDimensions dimensions,
Map<String, Object> data, List<String> activeSections)`. The compact constructor normalizes
`activeSections` the same way it already normalizes `data`: a **defensive, unmodifiable,
order-preserving copy** that **tolerates null** (an omitted JSON field), collapsing `null` to an empty
list. An omitted / null / empty `activeSections` therefore means "no optional sections added" -- which
is the pre-M2 behaviour for a document that has no optional sections, so the change is
backward-compatible for every existing caller. **Rationale:** the frozen contract names this exact
shape; riding the new field on the existing request record keeps the `DocumentProjectionApi` port
method signatures unchanged (preview/generate still take one request), so no public method breaks and
`ModularityTests` sees the same named interface. **Alternative rejected:** a new overload / second
port method carrying the active-set -- widens the public surface for a field the existing request can
carry additively.

### D2: The gating rule lives in the one compiler -- render iff mandatory OR active

The compiler's per-section loop gains one predicate: render a section **iff**
`section.optional == false` (**mandatory**, always) **or** `activeSections.contains(section.title())`
(an **added optional** section). A section that fails the predicate is **skipped entirely** -- the
loop emits no `<section>`, no header, no field table, no clause list, nothing. Because the rule sits
in the **single** compiler that both faces share, PREVIEW and GENERATE gate identically; there is no
second code path that could diverge (parity by construction is preserved).

- **Matching is exact + case-sensitive on the declared section `title`.** The umbrella froze the
  section `title` as its identity (the same token M3's `FormSection` and M4's rail use), so
  `activeSections` matches titles verbatim. No trimming, no case-folding, no fuzzy match -- a title
  that does not match exactly simply does not activate a section.
- **An unknown title is ignored, never an error (D2a below).**
- **`showWhen` is orthogonal and unchanged.** Section gating decides whether a section renders *at
  all*; a rendered section's clauses are still individually included/dropped by the sandboxed
  `showWhen` DSL exactly as before. An un-rendered optional section evaluates **no** `showWhen` (its
  clauses never reach the evaluator), so activation is the outer gate and `showWhen` the inner one.

The compiler's `compile(...)` gains the active-set as an input value (e.g. `compile(effective, data,
activeSections)` or an equivalent parameter object). It stays a **pure function** of its inputs -- no
new I/O, no clock, no state -- so a given `(effective template, data, activeSections)` renders
identical bytes every time.

### D2a: An unknown title in `activeSections` renders nothing and is never an error (no oracle)

A title in `activeSections` that matches **no** declared section is **silently ignored**: the compiler
iterates declared sections and activates the optional ones whose title appears in the set; a
set-member that names no section simply never matches, so it contributes nothing and raises nothing.
This is deliberate and load-bearing:

- **Not an error oracle.** A typo, a stale title, or a probe all produce the **same silent outcome**
  -- a caller cannot distinguish "this title names a real optional section that just is not active"
  from "this title names nothing" by watching for an error, because there is never an error.
  Consistent with the stack's no-oracle discipline (form projection's 404-without-echo, the catalog's
  uniform 404).
- **Cannot introduce structure.** An `activeSections` entry can only **match and toggle on** a
  section the template already declared as optional; it can never conjure a section, field, clause, or
  markup. So the boundary "users toggle system-authored structure, never author it" holds even for a
  garbage input.

**Alternative rejected:** rejecting an unknown title with a `400`/validation error -- it turns the
field into an existence oracle for the template's section titles and makes a harmless client typo a
hard failure; silent-ignore is safer and matches the umbrella ("unknown title ignored, never an error
oracle").

### D3: Two faces -- PREVIEW from the request, GENERATE deferred (parity caveat, named follow-on)

The gating rule is uniform; the two faces differ only in **where the active-set comes from**:

- **PREVIEW** (`DocumentProjectionService` preview mode, driving `previewHtml` / `previewPdf`) passes
  **`request.activeSections()`** straight into the compiler. The live pane and the Download-PDF both
  reflect exactly the optional sections the user added -- and they remain byte-for-byte identical to
  each other (the within-face parity non-negotiable is untouched: one compile, one HTML, HTML == PDF
  source).

- **GENERATE** (`DocumentProjectionService` generate mode, driving `generate`) passes the active-set
  it is **given** on the request -- which today is **none**, because
  `AgreementDocumentService.generate(...)` constructs the request without `activeSections` (the
  additive field defaults to empty). So generate-as-draft renders **mandatory sections plus any
  explicitly-given active-set (default none)** -- i.e. mandatory-only today. Wiring the **persisted
  agreement's** active-set into generate requires save/sign to first **record which optional sections
  the agreement added**, which is **out of scope** here.

- **Parity caveat (documented, not silent).** Because generate's active-set is deferred, a **preview
  that added optional sections will differ from the signed draft** (which renders mandatory-only)
  until the named follow-on records the agreement's active-set and passes it into generate. This is a
  **temporary, tracked** gap between the *preview face* and the *generate face* -- it is **not** a
  break of the within-face byte-for-byte parity (preview HTML still equals its own PDF source; a
  generated draft's HTML still equals its own PDF source). The umbrella (flow-journal section 6,
  "`activeSections` on generate/signing") names this as a deferred follow-on; this CR records the
  caveat in the spec so it is a known box, not a surprise.

**Rationale:** the compiler must gate uniformly (one rule, one path, parity by construction); only the
active-set *source* is face-specific. Deferring generate's source keeps this CR bounded to the
projection layer (no signing schema change) while making the shape complete and the gap explicit.
**Alternative rejected:** blocking this CR on persisting the active-set (drags in a signing migration
and save/sign wiring -- a much larger, cross-module change) or, worse, having generate silently invent
an active-set (a hidden divergence). Deferred-and-documented is the honest, minimal cut.

### D4: The boundary and invariants are preserved; this is a toggle of system-authored structure only

- **Markup/data boundary.** `activeSections` toggles a **template-declared** optional section on/off;
  it cannot introduce structure the template did not declare (D2a). Data values stay HTML-escaped at
  compile; clause text stays system-owned and escaped; the sandboxed `showWhen` DSL is unchanged. No
  new expression/DSL surface.
- **Statelessness.** The stateless preview endpoint still persists nothing and stays
  `Cache-Control: no-store`; `activeSections` is a transient request field.
- **Never-log.** The composed HTML, PDF bytes, submitted values, and the `activeSections` list are
  never logged. (Titles are not PII, but the list is not logged either -- no new log line is added.)
- **Module boundary.** The new field is on the existing public request record; the compiler and
  service stay package-private in `documents.template`; the public port signatures are unchanged.
  `ModularityTests` stays green.

## Section-gating flow (ASCII sketch)

```
  DocumentProjectionRequest { dimensions?, data, activeSections: [ "Rent Escalation", "Pets" ] }
        |
        v
  DocumentProjectionService
        |  PREVIEW  -> activeSet = request.activeSections()
        |  GENERATE -> activeSet = request.activeSections()  (today: none, from AgreementDocumentService)
        v
  TemplateCompiler.compile(effective, data, activeSet)     [ one compiler, one path -> parity ]
        |
        |  for each declared section:
        |     render IFF  section.optional == false            (mandatory  -> always)
        |            OR   activeSet.contains(section.title())   (optional   -> only when added)
        |     else: emit NOTHING (no header, no fields, no clauses)
        |     a title in activeSet that matches no section: ignored, no error (D2a)
        v
  self-contained, escaped HTML  ==  (byte-for-byte)  PDF HTML source     [ within-face parity ]

  Parity caveat (D3): preview activeSet comes from the client; generate's is deferred (default none),
  so a preview WITH added optionals differs from the signed draft (mandatory-only) until a named
  follow-on records the persisted agreement's active-set.
```

## Risks / Trade-offs

- **Preview vs signed-draft divergence (the parity caveat).** Until the active-set is persisted, a
  user can preview a document with optional sections that the signed PDF will not contain. Mitigation:
  documented explicitly (D3, the spec's parity requirement, and the umbrella's follow-on); M4's picker
  UX should not imply the optional sections it adds are already in the signed draft. Closed by the
  named follow-on (record + pass the active-set into generate).
- **Title as the activation key.** `activeSections` keys on the section `title`, so two sections
  sharing a title (or a title renamed in the template) would mis-key. Mitigation: section titles are
  the frozen section identity across M2/M3/M4 (unique within a definition by the umbrella's model);
  M0/M1's validation keeps titles well-formed. If titles later prove non-unique, a stable section id
  is the follow-up -- but the umbrella froze title as the key.
- **Case-sensitive exact match surprises a caller.** A client sending a differently-cased title will
  silently fail to activate a section. Mitigation: M4 builds `activeSections` from the **same schema**
  that carries the titles (it echoes titles it was given, never hand-types them), so the values match
  by construction; the silent-ignore is the safe failure mode (no error, no wrong structure).
- **Empty-default masking a real "none".** An omitted `activeSections` and an intentional empty list
  are indistinguishable (both == none). Accepted: for gating there is no behavioural difference
  between "sent nothing" and "sent an empty list" -- both render mandatory-only, which is correct.

## Open Questions

- **Active-set persistence shape (the follow-on).** When save/sign records which optional sections an
  agreement added, does it store the titles (mirroring `activeSections`) or stable section ids? A
  stable id survives a title edit; titles match the current wire contract. Proposing to settle this in
  the follow-on CR that wires generate's active-set, not here.
- **Section identity granularity.** The umbrella keys activation on the section title. If a future
  template needs two same-titled optional sections, a section id becomes necessary. Deferred to that
  need; out of scope here.
