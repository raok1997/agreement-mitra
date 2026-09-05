## Why

The templating engine (M0-M5) can resolve, project, compile, and pin any published
`(state, type)` template, and the frontend picker + dynamic form already carry a chosen
`(state, type)`. But the **last mile is missing**: an agreement never records *which*
template the user picked, and generate-as-draft always renders the **default** `(IN,
residential)` template regardless. So a Telangana agreement previews the TG document but
gets **signed against the national default** -- a break of the "what you preview is what
you sign" guarantee, and the reason the picker was locked to the single default (the
flow-journal "Gaps A + B" last-mile).

This change closes both gaps so the rental agreement is genuinely template-driven end to
end, then lifts the picker constraint that was the completion signal.

## What Changes

- **Gap A -- record the selected template at create.** `CreateAgreementRequest` gains
  OPTIONAL `state` + `type`. When both are supplied, `AgreementService.create` resolves the
  published template via the public `TemplateCatalogApi.publishedTemplateIdFor(state, type)`
  and records its id through the existing server-managed `Agreement.selectTemplate` path
  (never a client-settable UUID). An `(state, type)` no published template covers is rejected
  cleanly (RFC 9457, no echoed input). When absent, today's default behaviour is preserved.
- **Gap B -- dimension-aware generate + per-agreement preview.** `AgreementDocumentService`
  now resolves the agreement's selected template's `(state, type)` via
  `TemplateCatalogApi.detail(...).dimensions()` and passes those `DocumentDimensions` to the
  document projection for **both** `renderForDraft` and `renderPreview`. A `null` `templateId`
  keeps `null` dimensions (default, backward-compatible). The reproducibility pin
  auto-corrects: `renderForDraft` returns the identity of what it actually rendered, so the
  stored draft now pins the **selected** effective template, not the default.
- **Fixture: TG `stampDuty` default.** The TG state layer's `stampDuty` field (required, no
  aggregate column) gains a system-authored default so generate-as-draft renders it from the
  template default -- consistent with "extra template fields render from defaults" (no
  attributes store). Both the live preview and the signed draft use that default, so parity
  holds.
- **Frontend.** `client.ts` create passes the picked `(state, type)`; `CaptureForm` threads
  them from its props into the create payload and hides/strips `stampDuty` (non-persisted).
  The **M6 picker "Coming soon" lock is lifted** -- every published template (IN + TG) is now
  selectable, because generate-as-draft is dimension-aware.

No new endpoint, no security-matcher change, no migration (the `template_id`,
`template_content_hash`, `template_layer_versions` columns already exist from V8/V9), no
dependency/lockfile change. No new signing-status FSM state (selection at create; render +
pin at the existing generate-as-draft step).

## Capabilities

### Modified Capabilities
- `agreement-management`: create MAY now carry an OPTIONAL `(state, type)` selection; when
  present the server resolves and records the published template id server-side, and rejects
  a pair no published template covers (no-oracle 404). Absent selection is unchanged.
- `draft-ingestion`: the system-generated draft is now rendered from the agreement's
  **selected** effective template (dimension-aware) and the reproducibility pin records that
  selected template; an agreement with no selection still renders the default.
- `agreement-preview`: the on-screen preview is rendered from the agreement's **selected**
  effective template too, so the previewed document is the document that gets generated and
  signed (parity for non-default templates).

## Impact

- **`signing` module**: `AgreementService` and `AgreementDocumentService` gain a dependency on
  the public `documents.api` `TemplateCatalogApi` (the module already depends on
  `DocumentProjectionApi`); no `documents.template` type crosses the boundary -- only a `UUID`
  and the DTO dimensions. `ModularityTests` stays green.
- **`documents` module**: unchanged code; one fixture (`state-TG.patch.yaml`) gains a field
  default.
- **Frontend**: `client.ts` (create input), `CaptureForm.vue` (thread dimensions + hide
  `stampDuty`), `TemplatePicker.vue` + `App.vue` (M6 constraint lifted).
- **No** change to: endpoints, `SecurityConfig`, the signing FSM, stamping, the eSign/webhook
  flow, the DB schema, or dependencies.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No new PII.** This change records
  a system-owned template **UUID** on the agreement and resolves template **dimensions**
  (`state`/`type` selection tokens) -- integrity/selection metadata, never party PII, never an
  Aadhaar number, OTP, virtual id, or secret. The rendered PDF's party PII is unchanged from
  the existing generate-as-draft path (object storage, never logged).
- **How redacted/secured?** The template id is server-sourced from the catalog (never a
  client body field). An unknown `(state, type)` is rejected via the app-wide no-oracle
  contract -- `GlobalExceptionHandler` renders a fixed 404 detail that never echoes the
  requested dimensions, so a client cannot probe which pairs exist. Submitted values, composed
  HTML, and PDF bytes remain never-logged (inherited).
- **Sandbox + dummy data only?** Preserved -- local rendering + local MinIO; the TG default is
  dummy stamp-duty content; no live provider, no real PII, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- selection happens at create; render
  + pin happen at the existing generate-as-draft step; no new state, no new transition.
- **Async signing / webhook flow touched?** **None** -- no sequence change; generation
  precedes signing exactly as before.
