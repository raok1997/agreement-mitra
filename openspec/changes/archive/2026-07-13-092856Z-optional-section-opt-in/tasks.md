> **Prerequisite:** this change is module **M2** of `agreement-document-format`. It **depends on**
> **`template-document-metadata`** (M0 -- the per-section `optional` flag) and
> **`document-artifact-layout`** (M1 -- the reworked artifact-layout compiler). Apply **both** first;
> reuse their `Section.optional` fact and the M1 compiler (do not redeclare or re-lay-out). This CR is
> **pure `documents` + api**: one additive field on the public request record, one gating rule in the
> compiler. **No database migration, no new dependency, no `gradle.lockfile` change.**
>
> Windows note (project memory): run tests via gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
> and `-Duser.timezone=Asia/Kolkata`. Write all files in pure ASCII (the PII/secret guard fails closed
> on non-ASCII).

## 1. Public request shape -- `activeSections` (package `in.agreementmitra.documents.api`)

- [x] 1.1 Add `activeSections` (`List<String>`) to the public `DocumentProjectionRequest` record:
  `{ dimensions?, data, activeSections }` (the frozen section 4 contract). In the compact constructor,
  normalize `activeSections` exactly as `data` is normalized -- a defensive, **unmodifiable,
  order-preserving copy that tolerates null** (collapse `null` to an empty list). Update the record's
  Javadoc to state `activeSections` is the added **optional section titles**, system-owned structure
  (never party data), and that omitted/null/empty means "no optional sections added". Do **not** widen
  any `DocumentProjectionApi` port method signature (the new field rides the existing request record).

## 2. Compiler gating -- render iff mandatory OR active (package `documents.template`, package-private)

- [x] 2.1 Thread the active-set into the single compile path: `TemplateCompiler.compile(...)` gains the
  active-set as an input value (e.g. a `List<String>`/`Set<String>` parameter). It stays a **pure
  function** of its inputs (no I/O, no clock, no state).
- [x] 2.2 In the compiler's per-section loop, render a section **iff** `section.optional == false`
  (**mandatory** -- always) **or** the active-set contains `section.title()` (an **added optional**
  section). A section that fails the predicate is **skipped entirely** -- emit no `<section>`, header,
  field table, or clause list. Matching is an **exact, case-sensitive** match on the declared section
  `title`. A title in the active-set that matches **no** declared section is **ignored** (renders
  nothing, raises nothing -- no oracle). Leave `showWhen` clause include/drop untouched (it is the
  inner gate on a rendered section; an un-rendered section evaluates no `showWhen`).

## 3. Projection-service faces -- PREVIEW from request, GENERATE deferred (package `documents.template`)

- [x] 3.1 `DocumentProjectionService` PREVIEW mode (`previewHtml` / `previewPdf`) passes
  **`request.activeSections()`** into the compiler, so the live pane and Download-PDF reflect the added
  optional sections and stay byte-for-byte identical to each other (within-face parity).
- [x] 3.2 `DocumentProjectionService` GENERATE mode (`generate`) passes the active-set it is **given**
  on the request (default none -- `AgreementDocumentService` constructs the request without
  `activeSections`, unchanged). Document in the service Javadoc that generate's active-set is
  **deferred** (mandatory plus any explicitly given; today none) and record the **parity caveat**: a
  preview with added optionals differs from the signed draft until a named follow-on records the
  persisted agreement's active-set.

## 4. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 4.1 **Request normalization:** `DocumentProjectionRequest` with `activeSections == null` yields an
  empty, unmodifiable, order-preserving list; a supplied list is copied defensively (mutating the
  source does not affect the record); order is preserved. (covers task 1.1)
- [x] 4.2 **Compiler gating (the behavioural core):** over an effective template with one mandatory and
  one optional section --
  - a **mandatory** section renders regardless of the active-set (empty and non-empty);
  - an **optional** section **absent** from the active-set contributes **nothing** (its header, field
    rows, and clauses are all absent from the compiled HTML);
  - the **same optional** section, once its title is in the active-set, **renders** (header + its
    fields/clauses appear);
  - a title in the active-set that matches **no** declared section is **ignored** -- compilation
    succeeds, raises no error, and adds nothing to the HTML. (covers tasks 2.1, 2.2)
- [x] 4.3 **Face wiring (service, mocked collaborators):** PREVIEW passes `request.activeSections()` to
  the compiler; GENERATE passes an empty active-set when the request carries none (asserting the
  deferred-generate behaviour). (covers tasks 3.1, 3.2)

## 5. Tests -- integration (fewer; real wiring + module boundary)

> Mirror the existing document-projection integration tests (real resolve + compile over the reference
> layer sets; `@Testcontainers(disabledWithoutDocker = true)` where infra is needed). On Windows run
> gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true` per the project memory.

- [x] 5.1 **TG preview differs by exactly the active optional section:** resolve + compile the Telangana
  set for the **same** `(dimensions, data)` twice over the stateless preview -- once with an optional
  section's title **in** `activeSections`, once **without** it. Assert the two compiled documents
  differ **exactly** by that section's content (the with-run contains the section's header + fields/
  clauses; the without-run contains none of them; every other section is byte-for-byte identical). An
  unknown title added to `activeSections` leaves the output unchanged (no error). (covers tasks 1.1,
  2.2, 3.1)
- [x] 5.2 **`ModularityTests` stays green:** the `activeSections` field rides the existing public
  `documents.api` `DocumentProjectionRequest`; the compiler and `DocumentProjectionService` stay
  package-private in `documents.template`; no public port signature widens and no cross-module reach-in
  is introduced. (covers tasks 1.1, 2.x, 3.x)

## 6. Wrap-up

- [x] 6.1 `./gradlew spotlessApply` then the targeted `documents.*` + `ModularityTests` suite green
  (Docker up; `TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`). Confirm **no new
  dependency and no `gradle.lockfile` change**.
- [x] 6.2 Note the standing follow-on: **record the active-set on the persisted agreement** and pass it
  into generate-as-draft so the signed draft honours the same optional sections a preview added --
  closing the parity caveat (D3). Tracked in the `agreement-document-format` flow-journal (section 6,
  "`activeSections` on generate/signing").
