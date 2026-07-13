> **Prerequisite:** this change is **module M3** of the `agreement-document-format` umbrella
> (see its `flow-journal.md`). It depends on **`template-document-metadata`** (M0), which adds the
> declarative per-section `optional` (bool, default false) and `render` kind
> (`parties | keyvalue | clauses | annexure`, default `keyvalue`) onto the `Section` record and the
> effective template. Apply **`template-document-metadata`** first; this CR only reads those fields and
> projects them out onto the form contract. It obeys the section 4 FROZEN CONTRACT:
> **`FormSection` -- `{ title, fields[], optional: bool, renderKind: string }`; field-less sections
> omitted from `FormSchema`.** Written in ASCII (the local PII/secret guard fails closed on non-ASCII).

## Why

`template-form-projection` projects an effective template into a `FormSchema` of ordered sections and
fields for the capture form. Today a `FormSection` carries only `{ title, fields }`, so the client has
**no way to tell a mandatory section from an optional one** and **no way to know how a section renders
in the document**. The `agreement-document-format` target (locked decision 2 + the section 1 flow)
needs exactly that: the SPA left panel must show **Mandatory** sections up front and put **Optional**
sections into an **Add-optional catalog** the user opts into. That mandatory/optional split and the
section render style are **declared in the template** (M0), not hardcoded in the frontend -- so the
form contract must surface them.

There is a second gap. The projector already skips a section **entry** that is a clause id (document
body, not a form input). But a section whose entries are **all** clause ids -- a clause-only /
document-only section such as the single "Now This Agreement Witnesseth" list -- still projects to a
`FormSection` with an **empty `fields` list**. That is document structure, not a capture step, and it
should not appear as a (fieldless) card in the capture form. This CR drops such sections from the
`FormSchema` entirely while they continue to render in the document (M1's compiler).

This is M3 on the umbrella dependency graph -- it depends only on M0 and runs in parallel with M1/M2.
Its output is consumed downstream by **`capture-mandatory-optional-ux`** (M4), which drives the section
rail and the opt-in catalog from these two new fields.

## What Changes

- **`FormSection` DTO gains two fields:** `optional` (bool) and `renderKind` (string), **projected
  straight from the effective template's `Section`** (`Section.optional`, `Section.renderKind` from
  M0). No derivation, no defaulting in the projector beyond passing through what the effective template
  declares. `optional == false` means a mandatory section; the render kind is the raw declared token
  (`parties | keyvalue | clauses | annexure`), carried as an opaque string the client uses to group /
  label.
- **Field-less sections are omitted from the `FormSchema`.** A section that projects to **zero** form
  fields (every entry is a clause id -- a clause-only / document-only section, e.g. the Witnesseth
  list) is **dropped** from `FormSchema.sections`. It is document structure, not a capture step. (The
  per-entry clause-id skip already existed; this adds the whole-section drop when nothing is left.)
- **Projection stays deterministic and data-independent.** The two new fields are read from the
  effective template only; no user data, clock, IO, or randomness enters, and `showWhen` is still not
  evaluated. Same effective template -> equal `FormSchema` (including the new fields and the omission),
  so the schema stays cacheable per `(state, type, layer-version-set)`.
- **Frontend TS mirror types gain the fields (downstream touch, noted not built here):** the
  `frontend/src/api/templateForm.ts` `FormSection` interface will gain `optional: boolean` and
  `renderKind: string` to mirror the backend DTO exactly. This is a **downstream/frontend touch
  consumed by `capture-mandatory-optional-ux` (M4)**; the rail/catalog UI that uses these fields is M4,
  not this CR.

**Explicitly not in this change** (each owned elsewhere): the document layout / render-kind dispatch
in the compiler (M1 `document-artifact-layout`); the opt-in `activeSections` preview behavior (M2
`optional-section-opt-in`); the section-rail UI, the Add-optional catalog, and the forced-mandatory
Save gate (M4 `capture-mandatory-optional-ux`); and the `Section.optional` / `Section.renderKind`
schema itself plus loader/validator/canonical-hash carriage (M0 `template-document-metadata`). This CR
is only the **form-schema projection** of the two section attributes plus the field-less-section drop.

## Capabilities

### Modified Capabilities

- `template-form-projection`: a projected `FormSection` now carries the section's **`optional`** flag
  and **`renderKind`** token (projected verbatim from the effective template's `Section`), so the
  client can mark Mandatory vs Optional and build the Add-optional catalog; and a section that projects
  to **zero form fields** (a clause-only / document-only section) is **omitted** from
  `FormSchema.sections`. Projection remains a pure, deterministic, data-independent function of the
  effective template, exposes only system-owned metadata, and keeps the `documents` module boundary
  clean (the two fields cross only on the public `FormSection` DTO).

## Impact

- **`documents` module (backend):** `FormSection` (public `documents.api` DTO) gains `optional` +
  `renderKind`. `FormProjector` (package-private, `documents.template`) reads `Section.optional` /
  `Section.renderKind` (from M0) and passes them onto each `FormSection`, and skips building a
  `FormSection` when its projected `fields` list is empty. No new named interface, no widened record
  visibility -- `ModularityTests` stays green (the same one `documents.api` interface, one field-bearing
  DTO extended).
- **Contract shape (frozen, section 4):** `FormSection { title, fields[], optional: bool,
  renderKind: string }`. Additive to the JSON body -- existing consumers that ignore the new fields are
  unaffected; consumers of `FormSchema.sections` see clause-only sections disappear (intended).
- **Frontend:** `frontend/src/api/templateForm.ts` `FormSection` mirror type gains `optional: boolean`
  + `renderKind: string` (kept exactly mirroring the DTO). No UI change here -- the rail/catalog that
  reads them is **M4** (`capture-mandatory-optional-ux`). Called out so the mirror is not forgotten.
- **Dependencies:** **none added.** No new library; JSON serialization uses the Jackson already
  provided by Spring Boot. **No `gradle.lockfile` change**, nothing new on the OSV `securityScan`
  surface, no `package-lock.json` change.
- **Data / schema:** **none.** No migration, no DB touch -- projection is a pure in-memory transform.
- **No** change to: the form HTTP endpoint / caching / ETag behavior, `showWhen` handling, the
  resolver, effective-template identity, the document compiler, or any signing/webhook flow.
- **Depends on** `template-document-metadata` (M0) for `Section.optional` / `Section.renderKind`; runs
  in parallel with M1/M2; feeds `capture-mandatory-optional-ux` (M4).

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** `optional` is a boolean template
  attribute and `renderKind` is a system-authored layout token from a closed vocabulary
  (`parties | keyvalue | clauses | annexure`). Both are **system-owned template metadata** -- the shape
  of a form, never an instance of anyone's data. No government identity number, one-time code, virtual
  id, signer name, address, or party PII, and no secret material, is read or emitted. The projector
  still reads **no user data** at all.
- **Markup/data boundary held.** `renderKind` is a closed enum token carried as an opaque string, not
  markup and not an executable expression; `optional` is a boolean. Neither introduces a DSL surface.
  `showWhen` is still **carried verbatim and never evaluated** by the projector.
- **Determinism / no data leak preserved.** Projection stays a pure function of the effective template:
  no clock, IO, randomness, or user data. The same effective template yields an equal `FormSchema`
  (new fields included, field-less sections omitted), so it remains safely cacheable per version and
  the response body still leaks no user data.
- **Field-less-section omission leaks nothing.** Dropping a clause-only section removes a fieldless
  entry from the schema; it exposes no additional metadata and no document body (clause text is never
  in the `FormSchema` -- clause ids were already excluded from form fields).
- **Module boundary intact.** The two new fields cross the boundary only on the **public** `FormSection`
  DTO over the existing `documents.api` named interface; `FormProjector` stays package-private and no
  `documents.template` internal type leaks. `ModularityTests` stays green.
- **Sandbox + dummy data only?** Preserved -- no live provider, credential, or env var is touched;
  tests use dummy/system-authored template fixtures.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
