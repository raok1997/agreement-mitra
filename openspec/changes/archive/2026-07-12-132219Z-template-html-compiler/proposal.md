## Why

`template-resolution-engine` composes `(state, type)` into one immutable, hash-pinned
**effective template** and delivers a sandboxed `showWhen` DSL -- parser, validator, and a pure
**`ShowWhenEvaluator` that it deliberately does not wire to any render**. Nothing yet turns an
effective template **plus user data** into a document.

This change is the **first of four** increments that split the retired umbrella
`template-document-projection` (see
`openspec/changes/archive/2026-07-12-superseded-template-document-projection/`). It lands the
**pure engine** only -- *delivered-but-unwired*, exactly the pattern the resolution engine used for
`ShowWhenEvaluator`:

- a package-private **`TemplateCompiler`** that renders the effective template's sections/clauses as
  **system-owned markup** and fills each clause's `{{slot}}` with the **HTML-escaped** value from a
  data map, finally running the resolution engine's **`ShowWhenEvaluator` with real data** to
  include/drop conditional clauses (the delivered-but-unwired evaluator's first data-driven use);
- a package-private **`SubmittedDataValidator`** that checks a data map against the effective
  template's field schema (type, `min`/`max`, `minLength`/`maxLength`/`pattern`, `enum`) and
  **coerces** each value to the operand type the resolver validated, with two tiers (preview
  tolerates missing; generate enforces `required`).

It wires the compiler to **no** render path, **no** HTTP endpoint, and **no** signing change -- so
its blast radius is zero and it is fully unit-testable. CR-2 (`document-projection-render`) wires it
into the Gotenberg path and exposes it over HTTP.

**Why this is worth isolating.** It is the safety-critical core: the markup/data boundary
(escape-at-compile-time) and the "`showWhen` fires **only** through the sandboxed DSL, never
Thymeleaf/SpringEL" invariant both live here, and both are cleanly assertable in unit tests without a
Spring context, Gotenberg, or a database.

## What Changes

- Add a package-private **`TemplateCompiler`** in `in.agreementmitra.documents.template` (co-located
  with the definition/resolution records, so it reuses the package-private `EffectiveTemplate`,
  `Clause`, `Field`, `Section`, `ShowWhenParser`, and `ShowWhenEvaluator` with **no record
  visibility widened**): `compile(EffectiveTemplate, Map<String,Object> data) -> String html`.
  - Emits the document skeleton (sections, headings, ordered clause list) as **system-owned markup**.
  - Fills each `{{slot}}` with the **HTML-escaped** value; a missing/blank slot renders an **escaped
    placeholder** (never a bare `null`, never unescaped).
  - For each clause carrying a `showWhen`, parses it with `ShowWhenParser` and evaluates it with
    `ShowWhenEvaluator` over the real data, **including the clause iff true** and closing up numbering
    otherwise. A referenced value that is absent is treated **deterministically** (clause dropped),
    never an exception that leaks a value.
  - The composed HTML is **self-contained** (fonts by family name, no external URLs). The composed
    HTML is **never logged**.
- Add a package-private **`SubmittedDataValidator`** + a **`ProjectionMode`** (preview/generate) in
  the same package: validate present values against `type` + `FieldValidation`, coerce to the
  operand type, fill declared defaults for absent fields, enforce `required` in generate mode only.
  Errors cite field **keys/rule tokens** only, **never** a data value.
- Add a root-package **`DocumentDataInvalidException`** (shared kernel, beside the other app-wide
  exceptions) carrying `FieldErrorDetail` violations, and map it in `GlobalExceptionHandler` to the
  RFC 9457 contract (`application/problem+json`) with an `errors[]` list. The handler mapping is
  passive here (no endpoint triggers it yet); CR-2 adds the endpoint whose integration test exercises
  the HTTP rejection.

**Explicitly not in this change** (later increments): the `HtmlPdfRenderer` seam, the
`DocumentProjectionService`, any HTTP endpoint, retiring `TemplateAssembler`, the signing rewire, the
reproducibility pin/migration, and the frontend. This CR draws HTML in memory and stops there.

## Capabilities

### New Capabilities

- `template-document-projection` (partial -- the engine): a package-private `TemplateCompiler` that
  fills HTML-escaped slots and fires the sandboxed `showWhen` evaluator with real data to
  include/drop clauses, and a package-private `SubmittedDataValidator` that validates + coerces a
  submitted data map against the effective field schema (preview vs generate tiers). Both are pure
  (no I/O, no Spring context) and unwired to any render or HTTP surface.

## Impact

- **`documents` module**: `TemplateCompiler`, `SubmittedDataValidator`, and `ProjectionMode` added to
  `in.agreementmitra.documents.template`, all **package-private** and co-located with the records
  (**no record visibility widens**). Nothing consumes them yet (as `ShowWhenEvaluator` was delivered
  unwired). `ModularityTests` stays green (no new named interface, no cross-module reach-in).
- **Root package (shared kernel)**: `DocumentDataInvalidException` added; `GlobalExceptionHandler`
  gains one mapping to the RFC 9457 contract.
- **Dependencies**: **none added.** HTML escaping uses `org.springframework.web.util.HtmlUtils`
  (already on the classpath); the compiler is plain Java over existing records. **No
  `gradle.lockfile` change**, nothing new on the OSV `securityScan` surface.
- **No** change to: the render path, any HTTP endpoint, the signing module, the schema, or the
  frontend.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No Aadhaar/OTP/VID or secret.** The
  compiler renders **party PII** (names, addresses, rent, dates) into an in-memory HTML string from a
  supplied dummy data map; no data is collected, stored, transmitted, or logged. No government
  identifier, biometric, one-time code, or credential is involved.
- **How redacted/secured?**
  - **Escape at compile time (markup/data boundary):** every `{{slot}}` is filled with an
    **HTML-escaped** value, so a user value can never become document structure or active content;
    clause text is system-owned plain text and is escaped as literal text too. Users edit **data**,
    never markup.
  - **`showWhen` runs only in the sandbox:** conditional clauses are included/dropped **solely** by
    the resolution engine's hand-written boolean DSL evaluator over declared fields + literals --
    **never Thymeleaf SpringEL or any expression engine**; it has no method call, property
    navigation, indexing, or code path, so wiring it to render adds **no** code-execution surface.
  - **Never logged:** the compiler and validators write **no** log line containing the composed HTML
    or any submitted data value. Validation errors cite field keys/rule tokens only, never a value.
  - **Self-contained output:** the compiled HTML references fonts by family name with **no external
    URLs**, preserving the offline guarantee CR-2 relies on.
- **Sandbox + dummy data only?** Preserved -- pure in-memory rendering of dummy data; no provider, no
  real PII, no credentials.
- **Signing-status FSM transitions touched?** **None** -- this CR is a pure `documents`-module engine
  and touches no signing state.
- **Async signing / webhook flow touched?** **None.**
