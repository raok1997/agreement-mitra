> **Prerequisite:** apply **`template-document-metadata`** (M0) first -- this CR reads
> `Section.optional` / `Section.renderKind` off the effective template. This CR = **module M3** of the
> `agreement-document-format` umbrella; it obeys the section 4 FROZEN CONTRACT
> (`FormSection { title, fields[], optional: bool, renderKind: string }`; field-less sections omitted).
> Every behavioral change below ships a **unit AND an integration test** (the pyramid). ASCII only.
>
> **Windows test note (project memory):** run gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
> and `-Duser.timezone=Asia/Kolkata`; write files in pure ASCII (the PII/secret guard fails closed on
> non-ASCII).

## 1. Extend the FormSection DTO (package `in.agreementmitra.documents.api`, public)

- [x] 1.1 Add `optional` (`boolean`) and `renderKind` (`String`) to the public `FormSection` record:
  `FormSection { String title, List<FormField> fields, boolean optional, String renderKind }`. Update
  the Javadoc: `optional` marks a Mandatory (false) vs Optional (true) capture section; `renderKind` is
  the section's declared document render token (`parties | keyvalue | clauses | annexure`), carried as
  an opaque string for the client to group/label. Keep the `fields = List.copyOf(fields)` defensive
  copy. No other DTO changes; this stays on the existing `documents.api` named interface.

## 2. Project the two fields + omit field-less sections (package `documents.template`, package-private)

- [x] 2.1 In `FormProjector`, read `Section.optional()` and `Section.renderKind()` (M0) and pass them
  onto each `FormSection` verbatim (no derivation, no re-defaulting -- M0 already applied the declared
  defaults). Keep the existing per-entry clause-id skip (a clause id has no key in the fields index).
- [x] 2.2 In `FormProjector`, **omit** a section from `FormSchema.sections` when its projected `fields`
  list is **empty** (a clause-only / document-only section, e.g. the Witnesseth list) -- do not add a
  `FormSection` for it. Omission is driven purely by zero projected fields, independent of `renderKind`.
  Projection stays a pure function of the effective template (no clock/IO/randomness/user data;
  `showWhen` still not evaluated).

## 3. Frontend TS mirror (downstream touch; consumed by M4 `capture-mandatory-optional-ux`)

- [x] 3.1 In `frontend/src/api/templateForm.ts`, add `optional: boolean` and `renderKind: string` to the
  `FormSection` interface so it mirrors the backend DTO exactly. **No UI wiring here** -- the section
  rail, the Add-optional catalog, and the Mandatory/Optional affordances are M4
  (`capture-mandatory-optional-ux`), which consumes these fields. Keep `vue-tsc` clean.

## 4. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 4.1 **Optional + renderKind surfaced:** projecting an effective template whose `Section` is
  `optional = true` with a declared `renderKind` and at least one field key yields a `FormSection` with
  `optional == true` and that exact `renderKind`, its fields in authored order.
- [x] 4.2 **Mandatory section present and marked mandatory:** a field-bearing section declared
  `optional = false` projects to a present `FormSection` with `optional == false` and its declared
  `renderKind`.
- [x] 4.3 **Field-less section omitted:** an effective template with a section whose entries are all
  clause ids (zero field keys) projects a `FormSchema` whose `sections` **does not contain** that
  section, while an adjacent field-bearing section is still present in order.
- [x] 4.4 **Determinism / data-independence unchanged:** projecting the same effective template twice
  (mixing an optional field-bearing section, a mandatory field-bearing section, and a field-less
  section) returns **equal** `FormSchema`s, including the new fields and the omitted section, with no
  dependence on clock/IO/randomness/user data and no `showWhen` evaluation.

## 5. Tests -- integration (fewer; real wiring + module boundary)

> Mirror the existing documents integration tests; on Windows run gradle directly with
> `TESTCONTAINERS_RYUK_DISABLED=true` and `-Duser.timezone=Asia/Kolkata`.

- [x] 5.1 **Served FormSchema carries the new fields + omits document-only sections:** resolve a
  template through the real stack and assert the JSON from `GET /api/templates/form?state=..&type=..`
  carries `optional` + `renderKind` on a mandatory field-bearing section and does **not** include a
  clause-only (Witnesseth-style) section in `sections`.
- [x] 5.2 **`ModularityTests` stays green:** the change adds no new named interface (the `optional` +
  `renderKind` fields ride the existing public `FormSection` on `documents.api`), `FormProjector` stays
  package-private in `documents.template`, and no cross-module reach-in is introduced.

## 6. Frontend tests (Vitest, thin)

- [x] 6.1 A mirror/deserialization test that a `FormSection` fixture carrying `optional` + `renderKind`
  typechecks and round-trips through the `templateForm.ts` types (`vue-tsc` clean). Full rail/catalog
  behavior is M4's tests, not this CR's.

## 7. Wrap-up

- [x] 7.1 `./gradlew spotlessApply` green; run the `documents.*` suite + `ModularityTests`
  (`TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`) and the frontend `vitest run` +
  `vue-tsc -b`. Confirm **no new dependency and no `gradle.lockfile` / `package-lock.json` change**.
- [x] 7.2 Update the umbrella `flow-journal.md` section 5 tracking table: set
  `form-schema-section-semantics` (M3) status to `applied`. Note to M4
  (`capture-mandatory-optional-ux`) that the `FormSection.optional` / `renderKind` contract is now live.
