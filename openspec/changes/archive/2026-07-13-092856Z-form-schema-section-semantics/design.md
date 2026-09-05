## Context

`template-form-projection` established a pure, data-independent `FormProjector` that maps an
`EffectiveTemplate` into an immutable `FormSchema` of ordered `FormSection`s, each `{ title, fields }`.
The projector already treats a section **entry** as one of two things: a declared field `key` becomes a
`FormField`; a clause `id` is document body content and is skipped (it has no key in the fields index).
The `FormSchema` is system-owned metadata, carries no user data, and is cacheable per
`(state, type, layer-version-set)` because projection is deterministic.

The `agreement-document-format` umbrella (this CR = **module M3**, see `flow-journal.md`) moves
mandatory/optional and section render style **out of code and into the template**. Module **M0**
(`template-document-metadata`) adds two attributes to the `Section` record and the effective template:

- `Section.optional` -- `bool`, default `false` (mandatory).
- `Section.renderKind` -- a closed token `parties | keyvalue | clauses | annexure`, default `keyvalue`.

This CR is the **form-schema projection** of those two attributes, plus a small structural rule: a
section that has **no form fields** is document structure, not a capture step, and is omitted from the
`FormSchema`. It depends only on M0 and is parallel with M1 (compiler) and M2 (opt-in preview).

FROZEN CONTRACT (umbrella section 4, obeyed here): **`FormSection` -- `{ title, fields[], optional:
bool, renderKind: string }`; field-less sections omitted from `FormSchema`.**

## Goals / Non-Goals

**Goals**

- `FormSection` carries `optional` (bool) and `renderKind` (string), **projected straight** from the
  effective template's `Section` -- no derivation, no re-defaulting in the projector.
- A section that projects to **zero** form fields (all entries are clause ids -- a clause-only /
  document-only section, e.g. the Witnesseth list) is **dropped** from `FormSchema.sections`.
- Projection stays a **pure, deterministic, data-independent** function of the effective template; the
  new fields and the omission add no clock/IO/randomness/user-data input and do not evaluate `showWhen`.
- The `frontend/src/api/templateForm.ts` `FormSection` mirror type gains `optional` + `renderKind`
  (kept an exact mirror; consumed by M4, not wired to UI here).
- Keep the `documents` module boundary clean and `ModularityTests` green.

**Non-Goals (owned by sibling CRs)**

- The `Section.optional` / `Section.renderKind` schema, loader, validator, and content-hash carriage --
  **M0 `template-document-metadata`**.
- The document layout and per-render-kind dispatch in the compiler -- **M1 `document-artifact-layout`**.
- The `activeSections` opt-in preview behavior -- **M2 `optional-section-opt-in`**.
- The section-rail UI, the Add-optional catalog, and the forced-mandatory Save gate -- **M4
  `capture-mandatory-optional-ux`** (which consumes this contract).
- Any server cache / ETag / 304 wiring -- unchanged from `template-form-projection`.

## Decisions

### D1: Project `optional` + `renderKind` verbatim from the effective template's `Section`

`FormProjector` reads `Section.optional()` and `Section.renderKind()` (both added by M0) and copies them
onto the `FormSection` it builds. The projector does **not** derive, override, or re-default either
value -- M0 already applies the declared defaults (`optional = false`, `renderKind = keyvalue`) when
resolving the effective template, so by the time the projector sees a `Section` the values are concrete.
`renderKind` crosses as an **opaque string** (the raw token), not an enum, so the DTO contract does not
couple the public `documents.api` surface to the internal render-kind enum and a future render kind does
not force a DTO change. Rationale: keep the projector a thin, faithful mapping; the mandatory/optional
truth lives in the template (locked decision 2), and the client only needs a string to group/label.
**Alternative rejected:** deriving `optional` from "has no mandatory fields" or similar -- that
relitigates the locked "declared in the template" decision and couples the form to field-level state.

### D2: Omit a section that projects to zero form fields

After collecting a section's `fields` (field-key entries only; clause ids already skipped per the
existing rule), if the resulting list is **empty** the projector **does not add a `FormSection`** for
that source section. Such a section is clause-only / document-only (e.g. the single "Now This Agreement
Witnesseth" list) -- pure document structure that M1's compiler still renders in the document, but which
is **not a capture step** and would otherwise show as an empty, confusing card in the form. The umbrella
(section 6, first open point) records the decision that a section MAY be document-only and is then
omitted from the `FormSchema` while still rendered in the document. **Alternative rejected:** emitting
the empty section and letting the frontend filter it -- pushes a structural rule into every client and
risks a fieldless "Optional" card leaking into the Add-optional catalog.

Edge note: a section with `renderKind` `clauses` but that *does* list one or more field keys would still
be projected (it has fields). Omission is driven purely by **zero projected fields**, not by the render
kind -- so the rule is unambiguous and render-kind-independent, matching the FROZEN CONTRACT wording
("field-less sections omitted").

### D3: Determinism and data-independence are unchanged

The two new inputs (`Section.optional`, `Section.renderKind`) are properties of the effective template,
which is itself a pure function of `(state, type, layer-version-set)`. Reading them adds no clock, IO,
randomness, or user data, and the projector still does not evaluate `showWhen`. The omission rule is a
deterministic function of the (already-deterministic) projected field list. Therefore the same effective
template still yields an **equal** `FormSchema` -- including the new fields and the set of omitted
sections -- and the schema stays cacheable per version with the same `contentHash`/`ETag` semantics. No
change to the HTTP endpoint or caching is needed or made.

### D4: Frontend mirror kept exact; UI consumption deferred to M4

`frontend/src/api/templateForm.ts` mirrors the backend DTO tree exactly (per the frontend conventions:
API types live in `src/api/`). Its `FormSection` interface gains `optional: boolean` and
`renderKind: string` so the mirror stays faithful and `vue-tsc` sees the real shape. This CR does **not**
build the rail, the Add-optional catalog, or any Mandatory/Optional affordance -- that is M4
(`capture-mandatory-optional-ux`), which consumes these fields. Keeping the type change here (a) makes
the contract change atomic and testable and (b) avoids a stale mirror. **Alternative considered:**
deferring the TS type entirely to M4 -- rejected because the mirror should track the DTO the moment the
DTO changes, and M4 can start against the accurate type.

## Risks / Trade-offs

- **Dependency on M0's field names.** This CR assumes `Section.optional()` and `Section.renderKind()`
  exactly as frozen in umbrella section 4. Mitigation: the FROZEN CONTRACT is treated as breaking-if-
  changed; apply M0 first. If M0 names the accessor differently, this CR's projector reads adjust, but
  the DTO contract (`FormSection.optional` / `.renderKind`) stays as frozen.
- **Omission changes what consumers see.** A consumer that expected every source section in
  `FormSchema.sections` now loses clause-only sections. Accepted and intended -- those are not capture
  steps; the only current consumer is the capture form (M4), built against this new shape. An
  integration test asserts a known field-bearing section is present so the rule does not over-drop.
- **`renderKind` as an opaque string vs enum.** A malformed render kind can only reach the DTO if M0's
  validator let it through (M0 rejects unknown render kinds). The DTO stays a permissive string on
  purpose so a future kind does not break the contract; the client treats an unrecognized token as a
  generic section. Accepted.
- **Frontend/backend mirror drift.** The TS type must match the DTO. Mitigation: a `vue-tsc` build plus
  the noted M4 handoff; the mirror is changed in the same CR as the DTO.

## Test strategy (pyramid)

- **Unit (many, no Spring, no IO)** -- against `FormProjector` with hand-built effective-template
  fixtures: (1) a field-bearing **optional** section projects with `optional == true` and its declared
  `renderKind`; (2) a **mandatory** field-bearing section is present with `optional == false`; (3) a
  section whose entries are all clause ids projects to **zero** fields and is **omitted** from
  `FormSchema.sections`; (4) determinism/data-independence unchanged -- two projections of the same
  effective template (mixing optional + mandatory + a field-less section) are equal, including the new
  fields and the omission.
- **Integration (fewer, real wiring)** -- resolve a template through the real stack and assert the
  served `FormSchema` (JSON over `GET /api/templates/form`) carries `optional` + `renderKind` on a
  mandatory section and omits the clause-only (Witnesseth-style) section; `ModularityTests` stays green
  (no new named interface, projector stays package-private, no cross-module reach-in).
- **Frontend (Vitest, thin)** -- a type/mirror check that `FormSection` carries `optional` +
  `renderKind` (deserializing a fixture); full rail/catalog behavior is M4's tests, not this CR's.
