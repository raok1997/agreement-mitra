# SUPERSEDED -- not implemented as a single change

This `template-document-projection` proposal was **decomposed** into four small,
independently-shippable increments and retired here **unimplemented** (mirroring the earlier
`superseded-agreement-document-render` split). Its design decisions (D1..D10), data-flow diagram,
and PII/security checklist are reused across the increments; nothing here was applied to the
codebase as a single change.

Superseded by (apply in dependency order):

- **CR-1 `template-html-compiler`** -- the pure engine, *delivered-but-unwired* (the pattern the
  resolution engine used for `ShowWhenEvaluator`). `TemplateCompiler` (escape-at-compile + `showWhen`
  through the sandboxed DSL) + `SubmittedDataValidator` (type/bounds/enum coercion, preview vs
  generate modes) + `DocumentDataInvalidException`. Unit tests only; touches no render path, no HTTP,
  no signing. Owns umbrella tasks 1, 2, 7 and the compiler/validation requirements.

- **CR-2 `document-projection-render`** -- wire the engine into the render path. Public
  `HtmlPdfRenderer` seam over `GotenbergClient`, `DocumentProjectionService`, the public
  `documents.api` `DocumentProjectionApi` + DTOs + `POST /api/templates/document/preview`,
  `SecurityConfig` permit. **Retires** `TemplateAssembler` / `rental-agreement.html` /
  `DocumentRenderer`; rewires the id-bound signing preview to the compiler; removes the superseded
  `POST /api/agreements/preview`. Owns umbrella tasks 3, 4, 5.1, 8.1--8.5, 8.8 and the parity /
  stateless-preview / two-tier / offline-never-log / `document-rendering` / `agreement-preview`
  requirements. **Depends on CR-1.**

- **CR-3 `agreement-template-pin`** -- reproducibility pin. `Agreement.pinEffectiveTemplate` +
  forward-only Flyway **`V9`** (adds only `template_content_hash` + `template_layer_versions`; never
  re-adds `template_id`, per V8's catalog-lands-first note) + generate-as-draft full render + pin.
  Owns umbrella tasks 5.2, 5.3, 8.6, 8.7 and the `agreement-management` pin requirement. **Depends on
  CR-2.**

- **CR-4 `document-capture-shell-wiring`** -- frontend. A `src/api/` document-preview client and
  wiring the `preview-centric-capture` shell's live pane / Download PDF / Save & continue to the new
  endpoints. Owns umbrella task 6. **Depends on CR-2 (endpoint); CR-3 (Save & continue pins).**

Each increment carries its own `proposal.md` / `tasks.md` / spec delta and goes through
apply -> validate -> archive on its own. The V8 `template_catalog` migration already reserved
`template_id` on `agreement`; CR-3 takes `V9` and adds only the two remaining pin columns.
