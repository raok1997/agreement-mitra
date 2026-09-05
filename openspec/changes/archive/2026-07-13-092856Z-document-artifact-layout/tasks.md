> **Prerequisite:** this change depends on **M0 (`template-document-metadata`)** for
> `Meta.document {title, subtitle, executionLine}` and `Section.renderKind`
> (`parties | keyvalue | clauses | annexure`) on the effective template. Apply M0 first; this CR only
> **consumes** those fields. It does **not** gate optional sections (that is M2, `optional-section-opt-in`)
> and does **not** author the `sets/rental/` content (that is M5, `rental-document-content-v2`).
>
> **Windows test notes (project memory):** run gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
> and `-Duser.timezone=Asia/Kolkata`; write all files in pure ASCII (the PII/secret guard fails closed
> on non-ASCII). Integration tests use Testcontainers (`@Testcontainers(disabledWithoutDocker = true)`)
> and need a running Docker daemon.
>
> **Applied notes (2026-07-13):**
> - **Reserved date-binding key** (D4): `TemplateCompiler.EXECUTION_DATE_KEY = "agreementDate"`. The
>   projection layer (`DocumentProjectionService`) resolves the execution date once -- submitted
>   `agreementDate` (coerced ISO string) when present + non-blank, else `LocalDate.now(clock)`
>   formatted `d MMMM yyyy` (`Locale.ENGLISH`) -- and passes the concrete value into `compile`, which
>   binds it on a **copy** of the data map, overriding any submitted value. The compiler reads no clock.
> - **Determinism trade-off (accepted, D3/locked decision 4):** with a blank `agreementDate` the
>   rendered date is SYSDATE, so the document **changes across calendar days** for the same data. The
>   injected `Clock` (`@Bean documentClock()` = `Clock.systemDefaultZone()`; tests inject
>   `Clock.fixed(...)`) makes the fallback deterministic in tests. The render is reproducible given
>   `(template, data, resolved date)`.
> - **`Section.render()` accessor:** the M0 record field is `render` (not `renderKind`); the compiler
>   dispatches on `section.render()`, null-guarded to `KEYVALUE`. `keyvalue` **retains** the existing
>   mixed grouping (fields -> `table.kv`, included clauses -> `ol.clauses`), so un-migrated content
>   renders unchanged; `parties`/`clauses`/`annexure` are new dedicated shapes.
> - **Dependency on M5 content (`rental-document-content-v2`), not applied here:** the reference
>   `sets/rental/` set does **not** yet declare `meta.document` or per-section `render` tags (that is
>   M5). So the resolve+compile integration tests (2.3 / 5.1) assert the artifact-layout **invariants
>   that hold for the current content** (section headings, `table.kv`, numbered `ol.clauses`, signature
>   block, `@page` + body padding, self-containment, parity, resolved-date rendering) for **both IN and
>   TG**; the header / party-card / annexure regions are fully covered by the compiler **unit** tests
>   over hand-built fixtures. End-to-end validation of the header + party cards over the reference set
>   lands when M5 authors the content.
> - **Font blobs are an ops drop-in** (`documents/fonts/*.ttf` absent in-repo -> `DocumentFonts.faceCss()`
>   returns `""`), so task 4.3's "Noto data-URI faces present" holds only where the TTFs are installed;
>   the always-true assertions (self-contained, no external URL, `@page` margins) run in-repo.
> - **Two existing integration tests touched:** (1) `AgreementPreviewIntegrationTest` asserted a clause
>   substring against **rendered PDF text** whose line-wrapping shifted under the new margins/serif;
>   it now normalizes whitespace before the substring check (content asserted, wrap points not coupled).
>   (2) `SigningModuleSliceTest` failed to load context because a **pre-existing (uncommitted)** change
>   added a `TemplateCatalogApi` collaborator to `AgreementDocumentService` while the slice mocked only
>   `DocumentProjectionApi`; added the matching `@MockitoBean TemplateCatalogApi` (the isolation idiom
>   the test's own javadoc prescribes). This second one is **not** caused by this CR.

## 1. Header from `meta.document` (package `in.agreementmitra.documents.template`, package-private)

- [x] 1.1 Rework `TemplateCompiler` to emit a **centred header** ahead of the sections from the
  effective template's `meta.document`: an escaped `title`, an escaped `subtitle`, and an escaped
  **execution line** whose `{{slot}}` fills are HTML-escaped via the existing slot-fill path (a missing
  slot renders an escaped `[ label ]` placeholder). The header text is system-authored template text and
  is escaped exactly as clause text is.
- [x] 1.2 **Unit test:** the header renders the escaped `title`, `subtitle`, and execution line from a
  `meta.document`; a `{{slot}}` in the execution line is filled with its escaped value; a title/subtitle
  containing markup renders as literal text (no structure injection).

## 2. Section body dispatch by `render` kind (package `documents.template`, package-private)

- [x] 2.1 Replace the entry-shape grouping heuristic in `appendSectionBody` with a **dispatch on
  `Section.renderKind()`**: `parties` -> a **party card** (label/value block, used for Owner + Tenant);
  `keyvalue` -> the **current label/value table** (`table.kv`, used for Property/Term/Financial);
  `clauses` -> a **numbered ordered list** (`ol.clauses`, the "Now This Agreement Witnesseth" list,
  still `showWhen`-gated with numbering closing up on a dropped clause); `annexure` -> a **bulleted
  list** (`ul.annexure`). Every field label, field value, and clause text stays HTML-escaped. An
  unknown kind falls back to `keyvalue` (never throws). Each section still renders its escaped `title`
  heading above the dispatched body.
- [x] 2.2 **Unit test:** each render kind emits its expected **escaped** structure -- `parties` a party
  card, `keyvalue` the label/value table, `clauses` a numbered `<ol>` (a false `showWhen` drops its
  clause and the numbering closes up), `annexure` a bulleted `<ul>`; a data value / label containing
  markup renders inert (injection-as-data stays literal text) in every kind.
- [x] 2.3 **Integration test:** resolve + compile the reference **`IN`** and **`TG`** sets and assert the
  produced HTML carries the artifact layout -- the `meta.document` header, party card(s), a
  "Terms of Tenancy" key/value table, and a numbered witnesseth clause list -- with the signature block
  at the foot.

## 3. Execution date resolved at the projection layer (compiler stays pure)

- [x] 3.1 Add a package-private `@Bean java.time.Clock` (`Clock.systemDefaultZone()`) and inject it into
  `DocumentProjectionService` (constructor injection). On the **single** compile path, resolve the
  execution date once: the submitted **`agreementDate`** value when present and non-blank, else
  `LocalDate.now(clock)` (SYSDATE). Pass the concrete resolved date **into** `compile` (new
  package-private `compile` parameter); do not read a clock inside the compiler.
- [x] 3.2 In `compile`, bind the resolved execution date under the **reserved date-binding key**
  `agreementDate` on a copy of the data map (the request data is not mutated), overriding any submitted
  `agreementDate`, so the header execution line (and any clause slot) fills from the resolved value.
  Document the reserved key in a code comment.
- [x] 3.3 **Unit test:** with a submitted non-blank `agreementDate`, the header/execution line shows that
  value; with a blank/absent `agreementDate` and a **fixed injected `Clock`**, it shows the clock's date
  (SYSDATE fallback is deterministic under the fixed clock); the compiler is a pure function of
  `(effective, data, resolved date)` (no clock read in `compile`).
- [x] 3.4 **Integration test:** a stateless preview over the reference set resolves the execution date
  from the injected clock when `agreementDate` is blank and renders it in the header, while persisting
  nothing and returning `Cache-Control: no-store`.

## 4. Serif body, kept Noto faces, real page margins (package `documents.template`)

- [x] 4.1 Update the compiler's self-contained stylesheet: body `font-family` becomes a **serif** Latin
  stack while the Noto Indic families stay in the stack; **keep** `DocumentFonts`' Noto `@font-face`
  data-URIs unchanged (a missing face still degrades to family-name). Add **real page margins** via CSS
  `@page { margin: ... }` plus body padding, so they hold on both the browser live pane and the
  Gotenberg PDF. Add no `url(...)` beyond the existing data-URI faces.
- [x] 4.2 **Unit test:** the compiled HTML carries the serif body stack (with the Noto Indic families
  retained) and page margins (`@page` + body padding) are present in the stylesheet.
- [x] 4.3 **Integration test:** the `@page` margins and Noto data-URI faces are present in the one
  compiled document handed to Gotenberg (self-contained -- no external URL), for both `IN` and `TG`.

## 5. Preview <-> PDF parity across the new layout

- [x] 5.1 **Integration test:** compile -> Gotenberg for the reference set renders a PDF (bytes begin
  with the PDF signature) and the **preview HTML equals the PDF's HTML source byte-for-byte** for the
  same effective template + data + resolved execution date, over both `IN` and `TG` (parity preserved
  by the one compiler / one compile path / one resolved date).
- [x] 5.2 **Integration test:** keep **`ModularityTests`** green -- the compiler, projection service, and
  the resolved-date plumbing stay package-private in `documents.template`; the public `documents.api`
  surface is unchanged by this CR (no new module, no widened record).

## 6. Wrap-up

- [x] 6.1 `./gradlew spotlessApply` green; run the `documents.*` suite + `ModularityTests`
  (`TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`) and confirm the new unit +
  integration tests pass. Confirm **no new dependency and no `gradle.lockfile` change**.
- [x] 6.2 Confirm the invariants held: preview == PDF (parity), every data value **and** every template
  text (title / subtitle / execution line / labels / clause text) HTML-escaped, `showWhen` still the
  sandboxed DSL, the render offline/self-contained, nothing logged, nothing persisted, and the signature
  block intact.
- [x] 6.3 Record the **determinism trade-off** (blank `agreementDate` -> header changes across calendar
  days) and the **reserved date-binding key** (`agreementDate`) in the change notes, and confirm the
  `IN` and `TG` documents read like the reference artifact before proposing archive.
