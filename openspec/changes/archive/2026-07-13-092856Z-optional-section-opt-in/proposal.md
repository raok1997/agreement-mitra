> **Umbrella:** this is module **M2** of the `agreement-document-format` decomposition
> (`openspec/changes/agreement-document-format/flow-journal.md`). It carries requester item **#6**
> ("an optional section contributes nothing until the user adds it"). It **consumes** the frozen
> section 4 contracts and **depends on** M0 (`template-document-metadata`, which declares the
> per-section `optional` flag) and M1 (`document-artifact-layout`, which reworks the compiler to emit
> the artifact layout). Apply **`template-document-metadata`** and **`document-artifact-layout`**
> first. This CR is **pure `documents` + api**: it adds one additive field to the public request
> shape and one gating rule to the compiler. **No database migration, no new dependency.**
>
> Written in ASCII on purpose (the local PII/secret edit guard fails closed on
> em-dash/arrows/curly quotes under Windows Git Bash).

## Why

M0 made a section's `optional` flag a **template fact** (declared, canonicalized, hashed). M1 taught
the one system-owned compiler to draw every section of the artifact layout. But today the compiler
still renders **every** section it is handed -- so an optional section shows up in the preview and the
PDF whether or not the user asked for it. The `agreement-document-format` umbrella (locked decision 1)
requires the opposite: **optional = opt-in add-on**. An optional section is **absent** from the
document until the user explicitly **adds** it from the left-panel catalog; once added it renders in
both the live preview and the signed PDF; mandatory sections always render.

That behaviour needs a channel from the capture UX to the compiler that says *which* optional sections
the user added -- and a gating rule in the compiler that honours it. This CR adds exactly that channel
(`activeSections` on the public projection request) and that rule (render a section iff it is
mandatory **or** its title is in the active set), and nothing else. It renders no new markup, declares
no new field, and touches no database. The capture UX that populates `activeSections` from an
add-optional catalog is **M4** (`capture-mandatory-optional-ux`); this CR freezes the contract M4
consumes.

The rule is deliberately a **toggle of system-authored structure only**: `activeSections` can turn a
template-declared optional section **on**, but it can never introduce a section, field, clause, or
scrap of markup the template did not already declare. An unknown or mistyped title in `activeSections`
renders **nothing** and is **never an error** -- it is not an existence oracle and not an input the
document can be built from. The markup/data boundary, single-compiler parity, and never-log invariants
are all preserved.

## What Changes

- **`DocumentProjectionRequest` gains `activeSections`** -- a `List<String>` of the **optional section
  titles the user has added**. It is **additive** on the public `documents.api` request shape:
  `{ dimensions?, data, activeSections }`. An omitted / null / empty list means "no optional sections
  added" (the pre-M2 behaviour for a document with no optional sections). The record normalizes null
  to an empty, unmodifiable, order-preserving copy, exactly as it already does for `data`.

- **The compiler renders a section iff it is mandatory OR active.** The compiler renders a section
  when `section.optional == false` (**mandatory** -- always rendered) **or** the section's `title` is
  present in `activeSections` (an **added optional** section). An optional section whose title is
  **not** in `activeSections` contributes **nothing** -- no header, no field rows, no clauses, no
  markup at all -- to either the preview HTML or the PDF. A title in `activeSections` that matches **no
  declared section** (an unknown/typo title) is **ignored**: it renders nothing and raises no error
  (no oracle). Matching is an exact, case-sensitive match on the declared section `title` (the section
  identity the umbrella froze).

- **Both faces defined, GENERATE deferred.** The gating logic is **one rule in the one compiler**, so
  the two projection faces share it:
  - **PREVIEW** takes `activeSections` **from the request** (`DocumentProjectionService` preview
    mode), so the live pane reflects exactly the optional sections the user has added.
  - **GENERATE**'s active-set is **deferred**. Save/sign does not yet record *which* optional sections
    an agreement added, so generate-as-draft renders **mandatory sections plus any `activeSections` it
    is explicitly given (default none)**. Wiring the persisted agreement's active-set into generate is
    **out of scope** here (a named follow-on). **Parity caveat:** a preview that added optional
    sections will differ from the signed draft (mandatory-only) until save/sign records the active-set
    -- this is the one, documented, temporary parity gap, distinct from the byte-for-byte
    preview==PDF parity that this CR preserves *within* a face.

- **Preserve the boundary.** No change to escaping (data + clause text stay HTML-escaped at compile),
  the sandboxed `showWhen` DSL (unchanged), statelessness (the preview still persists nothing), or the
  never-log invariant. `activeSections` carries **section titles only** (system-owned structure), not
  party data; it is not logged.

**Explicitly not in this change** (each a named follow-on / separate CR): the **capture UX** that
marks sections mandatory/optional and builds the add-optional catalog that populates `activeSections`
(M4, `capture-mandatory-optional-ux`); **recording the active-set on the persisted agreement** so
generate-as-draft and the signed draft honour it (the named follow-on that closes the parity caveat);
the **template `optional` flag** itself (M0, already applied); and the **compiler layout** the gating
sits inside (M1, already applied). This CR toggles sections; it does not author, capture, or persist
them.

## Capabilities

### Modified Capabilities

- `template-document-projection`: the compiler and the stateless preview become **optional-aware**.
  The public `DocumentProjectionRequest` gains an additive `activeSections` list (the added optional
  section titles); the compiler renders a section iff it is mandatory (`section.optional == false`) or
  its title is in `activeSections`; an unknown title is ignored (renders nothing, never an error). The
  stateless preview endpoint accepts `activeSections` and drives the compiler with it, so the live
  pane shows an added optional section and omits an un-added one. Single-compiler byte-for-byte parity
  (preview HTML == PDF HTML source) is preserved **within a face**; a **documented parity caveat**
  records that generate-as-draft's active-set is deferred, so a preview with added optionals differs
  from the signed draft until a named follow-on records the active-set. Escaping, the sandboxed
  `showWhen` DSL, statelessness, and the never-log invariant are unchanged.

## Impact

- **`documents` module**: `DocumentProjectionRequest` (public `documents.api` record) gains
  `activeSections` -- additive, backward-compatible (omitted == none). The package-private
  `TemplateCompiler` gains one section-gating rule; the package-private `DocumentProjectionService`
  threads `activeSections` into the single compile path (PREVIEW from the request; GENERATE from its
  given set, default none). The public `DocumentProjectionApi` port signatures are **unchanged** (the
  new field rides the existing request record). No record visibility widens; `ModularityTests` stays
  green (same one `documents.api` named interface; the compiler and service stay package-private in
  `documents.template`).
- **`signing` module**: **no change**. `AgreementDocumentService.generate(...)` constructs a
  `DocumentProjectionRequest` without `activeSections` (the additive field defaults to none), so the
  generate face renders mandatory-only exactly as today -- the deferred active-set. When the named
  follow-on records the agreement's active-set, it will pass it here; nothing in this CR does.
- **Frontend**: **no change in this CR.** M4 (`capture-mandatory-optional-ux`) adds the add-optional
  catalog and sends `activeSections` on the preview request; this CR only freezes the field it sends.
- **Dependencies**: **none added.** No `gradle.lockfile` change; nothing new enters the OSV
  `securityScan` surface.
- **Data / schema**: **none.** No migration; `activeSections` is a transient request field, never
  persisted by this CR. (Persisting the active-set is the named follow-on.)
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, object storage,
  the reconciliation job, the resolver, the effective-template identity, or the render path's PDF leg.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** `activeSections` is a list of
  **section titles** -- system-owned template structure (e.g. "Rent Escalation", "Pets"), authored in
  the template, never signer data. It carries no government identity number, one-time code, virtual
  id, name, address, or party PII, and no secret material. It is not logged.
- **Markup/data boundary held.** `activeSections` can only **toggle a template-declared optional
  section on or off**; it can never introduce a section, field, clause, or scrap of markup the
  template did not declare. All rendered structure remains **system-owned**; all data values remain
  **HTML-escaped at compile time**. No user-authored markup and no new expression/DSL surface is
  introduced -- the sandboxed `showWhen` evaluator is unchanged.
- **No existence oracle.** A title in `activeSections` that matches no declared section renders
  **nothing** and raises **no error** -- the same, silent outcome for a typo and for a section that
  simply is not in this template. A caller cannot probe which sections a template declares by toggling
  titles and watching for an error, because there is never an error.
- **Statelessness preserved.** The stateless preview still persists **nothing** (no row, no blob, no
  draft) and stays `Cache-Control: no-store`; `activeSections` is a transient request field, resolved
  per request and never stored by this CR.
- **Single-compiler parity preserved (within a face); one documented caveat.** Preview HTML stays
  byte-for-byte the PDF's HTML source for a given `(effective template, data, activeSections)`. The
  **one** relaxation is documented, not silent: generate-as-draft's active-set is deferred, so a
  preview with added optional sections differs from the signed draft (mandatory-only) until a named
  follow-on records the agreement's active-set. This is a temporary, tracked gap -- not a second
  renderer and not a divergence within either face.
- **Never-log invariant held.** Neither the composed HTML, the PDF bytes, any submitted data value,
  nor the `activeSections` list is written to any log at any level.
- **Sandbox + dummy data only?** Preserved -- no live provider, no real data, no credential or env var
  added.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
