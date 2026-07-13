## Context

This is the frontend wiring increment that points the `preview-centric-capture` shell at the live
`documents` preview endpoint. It was authored against `flow-journal` section 4, which named the
stateless preview `POST /api/agreements/preview` with a `{templateRef, workingSet}` body. That
contract has since moved (see the flow-journal section 8.2 correction).

> ASCII on purpose: the local PII/secret edit guard fails closed on em-dash/arrows/curly quotes under
> Windows Git Bash (project memory). Keep hand-written spec/journal edits ASCII.

## Decision D1 -- bind to the endpoint that actually shipped

The stateless preview now lives in the `documents` module, not `signing`:

- Endpoint: `POST /api/templates/document/preview` (`DocumentProjectionController`).
- Request body: `DocumentProjectionRequest { dimensions: {state, type} | null, data: Map<key, value> }`
  -- a FLAT field-key data map (the working set flattened across sections), NOT the old nested
  `signers[]` object. `dimensions` is optional; when omitted the backend resolves the reference
  default `(state, type)`.
- Content negotiation on `Accept`: `text/html` -> compiled, escaped HTML for the live pane (embed in a
  sandboxed iframe via `srcdoc`); anything else -> the Gotenberg PDF for Download PDF.
- Both variants are `Cache-Control: no-store`; the HTML variant carries an iframe-safe CSP
  (`default-src 'none'`, no `script-src`). The client persists and logs nothing.

The old `POST /api/agreements/preview` route no longer exists on the backend and is not in
`SecurityConfig`'s permit set, so the current `src/api/preview.ts` calls a dead route. This CR retires
that client. The tasks.md line items ("point the shell at the new endpoints") already assumed this
move; D1 makes the reconciliation explicit and records WHY the endpoint/shape differs from what the
journal originally froze.

## Decision D2 -- pass dimensions explicitly, sourced from the picker

Because M6 introduces the State x Type picker (`template-catalog` task 6.2), the shell now knows the
chosen `(state, type)`. The preview request MUST send those same dimensions so the previewed document
resolves the SAME effective template the form schema was projected from. The old client sent no
dimensions and relied on the backend default; that is only correct for the single reference template.
The new `postDocumentPreview(data, accept, dimensions)` carries dimensions through.

## Decision D3 -- the working set flattens to a field-key map

The form projection already keys every field by its `FormSchema` field `key`. The shell's working set
is section-keyed (`{ sectionId: { fieldKey: value } }`); the preview client flattens it to
`{ fieldKey: value }` (the existing `flatWorking()` helper). The well-known-key remapping in
`buildPreviewInput()` (tenantName/monthlyRent/...) is deleted -- it predates the schema-driven shell
and drops dynamic fields.

## Non-goals / carried forward

- Save & continue still targets the existing `create` + generate-as-draft endpoints (fixed columns);
  the schema-driven, dynamic-field-persisting submit is `agreement-attributes-and-pinning` (M5), not
  this CR. Dynamic fields are dropped on save until M5 lands (flow-journal 8.4).
- Preview/draft parity for non-default `(state, type)` is an M5 concern (flow-journal 8.5); this CR
  only makes the LIVE preview correct, not the stored draft.
- `showWhen` stays carried verbatim and unevaluated.
