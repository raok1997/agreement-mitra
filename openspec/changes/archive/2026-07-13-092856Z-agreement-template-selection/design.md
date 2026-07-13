## Context

Everything the last mile needs already exists as public `documents.api` seams:
`TemplateCatalogApi.publishedTemplateIdFor(state, type)` (the dimension-validation
authority), `TemplateCatalogApi.detail(id).dimensions()` (the recorded template's `(state,
type)`), and `DocumentProjectionApi.generate(new DocumentProjectionRequest(dimensions,
data))` (which already returns the rendered PDF **plus** the `EffectiveTemplateIdentity` to
pin). The `Agreement` aggregate already has `selectTemplate(UUID)`, `templateId()`, and
`pinEffectiveTemplate(...)` with their columns (V8/V9). `AgreementService.recordSelectedTemplate`
already exists as the server-managed setter, wired to nothing. This change is pure wiring
across two frozen seams -- no new engine code.

## Goals / Non-Goals

**Goals:**
- Record the picked published template on the agreement at create, server-side, no-oracle.
- Render (draft + preview) from the agreement's selected effective template; pin what was
  rendered.
- Lift the M6 picker constraint so all published templates are selectable.

**Non-Goals:**
- No **attributes store**: template-declared fields the aggregate lacks render from the
  template's declared defaults (out of scope; the deferred M5 attributes CR owns per-agreement
  custom values).
- No migration, no dependency change, no endpoint/security change, no FSM change.
- No caching/ETag work (deferred elsewhere).

## Decisions

### D1: Resolve + record at create, in the service, before persistence

`AgreementService.create` resolves `(state, type)` to a UUID **before** building/saving the
aggregate, so an unknown pair rejects with nothing persisted. Both dimensions must be
non-blank to select; either absent means "no selection" (default behaviour) -- the
backward-compatible rule. The resolution uses the public `TemplateCatalogApi` (Modulith-clean);
`signing` holds only the resulting `UUID`. **Alternative rejected:** resolving in the
controller -- the service is the transactional owner of create and already the natural place
for the server-managed selection.

### D2: Reject an uncovered `(state, type)` as a no-oracle 404

`publishedTemplateIdFor` returning empty raises `ResourceNotFoundException` -- the same
no-oracle 404 the catalog's own `detail(...)` uses for an unknown/unpublished id.
`GlobalExceptionHandler` renders a fixed constant detail and never echoes the requested
dimensions, so a client cannot probe which pairs exist. **Alternative considered:** a 400/422
validation error -- rejected to reuse the existing no-oracle contract verbatim (the catalog is
already the documented "dimension-validation authority").

### D3: Dimension-aware render via `detail(...).dimensions()`, `null` when unselected

`AgreementDocumentService.render` (shared by `renderForDraft` and `renderPreview`) reads
`agreement.templateId()`; when non-null it fetches `TemplateCatalogApi.detail(id).dimensions()`
and passes `new DocumentDimensions(state, type)`; when null it passes `null` (the projection
resolves its own default). Only the DTO dimensions cross the boundary -- no `documents.template`
type. The pin is **unchanged**: `renderForDraft` already returns the identity of the template
it rendered, so pinning that identity now records the **selected** template automatically.
**Alternative rejected:** storing `(state, type)` columns on the agreement -- redundant with
the already-recorded `templateId`, and would need a migration.

### D4: TG `stampDuty` gets a system-authored default (fixture)

The TG state layer adds `stampDuty` as **required** with no aggregate column. Generate mode
enforces required-present-or-defaulted, so without a default a TG generate-as-draft would 400.
Per the locked "extra template fields render from template defaults (no attributes store yet)"
scope, `stampDuty` gains a dummy default. Both the live preview and the signed draft then use
that default (the aggregate never carries a user value), so parity holds. The frontend hides +
strips `stampDuty` (added to `NON_PERSISTED_FIELDS`) so a user cannot set a value the draft
would ignore. **Alternative rejected:** mapping `stampDuty` from the aggregate -- there is no
column, and per-agreement custom values are the deferred attributes-store CR.

### D5: Lift the picker "Coming soon" lock

Once generate-as-draft is dimension-aware and single-sourced with preview, the parity risk
that justified locking the picker to `(IN, residential)` is gone. Every published template
becomes selectable. This touches M6-owned `TemplatePicker.vue` / `App.vue`; it is the signal
that `document-capture-shell-wiring` (M6, 6/7) can now archive.

## Risks / Trade-offs

- **Preview/draft parity for non-persisted fields.** Fields the aggregate does not persist
  (`furnished`, `registrationResponsibility`, `stampDuty`) render from **template defaults** on
  both faces -- parity holds, but a user cannot yet customise them. Accepted; the attributes
  store is the deferred M5 CR. The frontend hides them so the UI does not imply otherwise.
- **`detail(...)` fail-closed.** If a recorded `templateId` later becomes unpublished,
  `detail` raises 404 and generate/preview fail closed rather than silently falling back to the
  default. Correct for legal reproducibility; acceptable because published versions are
  immutable.
- **Double catalog read at generate** (`detail` on render + `publishedTemplateIdFor` at create)
  -- negligible, keeps each seam self-contained.

## Migration Plan

**No database migration.** `template_id` (V8) and `template_content_hash` /
`template_layer_versions` (V9) already exist. One fixture default, service/DTO wiring, and
frontend changes. No dependency change -> no lockfile change.

## Open Questions (resolved)

- **Reject vs. default on an unknown pair?** Reject (no-oracle 404) -- a client that names a
  `(state, type)` intends that template; silently defaulting would sign the wrong document.
- **Where does `stampDuty` get its value?** From the template default now; from the deferred
  attributes store when per-agreement customisation lands.
