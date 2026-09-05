> **First of four increments** superseding `template-document-projection`. Delivers the pure engine
> only, wired to nothing (as `ShowWhenEvaluator` was delivered unwired). Reuses the package-private
> `EffectiveTemplate` / `Field` / `Clause` / `Section` / `ShowWhenParser` / `ShowWhenEvaluator` from
> the definition + resolution CRs -- do not fork or widen them.

## 1. TemplateCompiler (package `in.agreementmitra.documents.template`, package-private)

- [x] 1.1 Add a package-private `TemplateCompiler` co-located with the records so **no record
  visibility is widened**: `compile(EffectiveTemplate effective, Map<String,Object> data) -> String
  html`. Emit the document skeleton (sections, headings, ordered clause list, signature block) as
  **system-owned markup**; render each clause's plain text with each `{{slot}}` replaced by the
  **HTML-escaped** value (escape both the literal clause text and the slot value -- the markup/data
  boundary). A missing/blank slot renders an **escaped placeholder** (the field label), never a bare
  `null` and never unescaped.
- [x] 1.2 Wire `showWhen`: for every clause carrying a `showWhen`, parse it via `ShowWhenParser` and
  evaluate it against the data via `ShowWhenEvaluator`, **including the clause iff true** and closing
  up its numbering otherwise. Route conditions **only** through the sandboxed DSL -- never
  Thymeleaf/SpringEL. A referenced value that is absent/uncoercible is treated **deterministically**
  (clause dropped), never an exception that leaks a value.
- [x] 1.3 Ensure the compiled HTML is **self-contained** (fonts by family name, no external URLs) so
  CR-2's offline / Chromium-network-denied guarantee holds; never log the composed HTML.

## 2. Server-side data validation (package `in.agreementmitra.documents.template`, package-private)

- [x] 2.1 Add a package-private `SubmittedDataValidator` + `ProjectionMode`: check a data map against
  the effective template's field schema -- each present value matches its `type` and its
  `FieldValidation` (numeric `min`/`max`, text `minLength`/`maxLength`/`pattern`, `enum` membership)
  -- and **coerce** each value to the operand type the resolver validated (`INT->Long`,
  `MONEY->BigDecimal`, `BOOL->Boolean`, `DATE->ISO String`, else `String`). Fill declared defaults for
  absent fields. Errors cite field keys / rule tokens only, **never** a data value.
- [x] 2.2 Two modes: **preview** validates present values and tolerates missing ones (no `required`
  enforcement); **generate** validates fully (every `required` field present-or-defaulted and valid).
  On failure raise `DocumentDataInvalidException` (root package) carrying `FieldErrorDetail`
  violations; render nothing.
- [x] 2.3 Map `DocumentDataInvalidException` in `GlobalExceptionHandler` to the app's RFC 9457 error
  (`application/problem+json`) with an `errors[]` list of field-key + rule-token entries (no value).
  The mapping is passive here (no endpoint triggers it yet -- CR-2 adds it); unit-test the mapping.

## 3. Tests -- unit (many, fast; no Spring context, no I/O)

- [x] 3.1 `TemplateCompiler` escaping: a slot value containing angle-bracket markup renders as literal
  text; the compiled HTML has no injected structure/active content; a missing slot renders an escaped
  placeholder, not `null`.
- [x] 3.2 `TemplateCompiler` showWhen: a clause with a true `showWhen` is included; a false one is
  dropped and numbering closes up; conditions run only through `ShowWhenEvaluator` (a `showWhen` that
  would be an expression-engine construct is not evaluated as code).
- [x] 3.3 `TemplateCompiler` partial data: an empty and a partially-filled data map each compile to a
  coherent document with placeholders; no bare `null` and no unescaped value.
- [x] 3.4 `SubmittedDataValidator`: present-value type/bounds/pattern/enum checks pass/fail correctly;
  coercion normalizes to operand types; preview mode tolerates missing + skips `required`; generate
  mode enforces `required`; error messages contain field keys/rule tokens and **no** data value.
- [x] 3.5 `GlobalExceptionHandler` maps `DocumentDataInvalidException` to a `400`/`422`
  `application/problem+json` whose `errors[]` carry field keys + rule tokens and **no** data value.
- [x] 3.6 Never-log: exercising the compiler and validators produces no log line containing the
  composed HTML or a submitted data value (a log-capturing unit assertion).

## 4. Wrap-up

- [x] 4.1 `./gradlew spotlessApply` then `./gradlew test` green; `ModularityTests` green (the compiler
  and validator stay package-private in `documents.template`; no new named interface, no cross-module
  dependency). Confirm **no new dependency and no `gradle.lockfile` change**.
- [x] 4.2 Note that this CR is *delivered-but-unwired*: nothing renders or serves the compiler yet.
  CR-2 (`document-projection-render`) factors the `HtmlPdfRenderer` seam, adds the
  `DocumentProjectionService` + `documents.api` port + `POST /api/templates/document/preview`, retires
  `TemplateAssembler`, and rewires the signing preview.
