## Context

CR-3b built the render + preview path: `AgreementDocumentService.renderPreview(id)` produces the
rental-agreement PDF from an `Agreement`. The draft-upload path already exists:
`DraftService.attachDraft(id, bytes)` validates the bytes are a PDF, **freeze-checks** (409 if a
signing request exists), stores to object storage under `drafts/{id}.pdf`, and records
`draft_pdf_key`. This increment wires those two together behind one endpoint. It adds almost no new
logic -- the value is the connection.

## Goals / Non-Goals

**Goals:**
- `POST /api/agreements/{id}/document` -> render + store as the signable draft; `200` + agreement id.
- Overwrite while no signing request exists; **lock (409)** once one does; `404` for unknown id.
- Frontend "Use this document" action that generates and confirms readiness for signing.

**Non-Goals:**
- No new persistence, mapping, or schema. No FSM/stamping/eSign change. No auth (anonymous, like the
  rest of the draft path). No template change (uses the CR-3b India-standard template).

## Decisions

### D-C1: Reuse render + the existing draft path; the controller orchestrates

The generate handler calls two existing, independently-transactional collaborators in order:

1. `byte[] pdf = agreementDocumentService.renderPreview(id)` -- CR-3b's read-only render (404 if the
   agreement is unknown; the Gotenberg call happens in its own short read-only transaction).
2. `draftService.attachDraft(id, pdf)` -- the existing write path: validate-PDF -> freeze-check ->
   store -> attach, in its own write transaction.

The controller orchestrates so each collaborator is invoked **through its own Spring proxy** (its
own `@Transactional`). A single service method calling both would self-invoke the read-only render
and bypass its proxy (lazy-signer init would fail) and would hold a DB transaction open across the
Gotenberg HTTP call. Two proxied calls avoid both. **Alternative rejected:** a combined
`@Transactional` service method -- long write transaction spanning an external HTTP render, and
self-invocation breaks the read-only render's transaction.

### D-C2: The freeze rule is inherited, not re-implemented

`attachDraft` already rejects with `409` (`ConflictException.draftFrozen()`, body `draft-frozen`)
when `signingRequestQuery.existsForAgreement(id)` is true -- for **any** signing-request state
(active or terminal), matching the upload path exactly. Generate gets identical
overwrite-until-signing-then-locked semantics **for free**, so a generated draft and an uploaded one
behave the same. The freeze check runs **before** the store, so a locked generate never overwrites
the stored blob. The wasted render (produced then rejected) has no persistent side effect.

### D-C3: The rendered PDF is a valid PDF for `attachDraft`'s magic-byte check

`attachDraft` validates the bytes begin with `%PDF-`. Gotenberg returns exactly that (`%PDF-1.4`),
so a generated document passes the same untrusted-input gate an upload does -- no special-casing.

### D-C4: Frontend "Use this document"

`src/api/client.ts` gains `generateAgreementDocument(id): Promise<void>` (POST, throws on non-2xx via
`describeProblem`). `CaptureForm.vue` shows a **"Use this document"** button alongside Preview once an
agreement exists; on success it confirms the document is saved as the signable draft and the flow can
proceed to signing. A `409` surfaces the friendly "already being signed" message. **Alternative
rejected:** auto-generating on preview -- preview must stay ephemeral (CR-3b); generation is the
deliberate commit.

## Risks / Trade-offs

- **PII now persisted** -- the generated draft carries party PII, stored in object storage (never
  Postgres), under the existing draft key; never logged (checklist in the proposal).
- **Render-then-409 waste** -- when locked, the render runs before `attachDraft` rejects; harmless
  (no persistent effect), and the lock is the correct authority at store time.
- **Double agreement load** -- `renderPreview` and `attachDraft` each load the aggregate; negligible,
  and keeps each collaborator self-contained.

## Migration Plan

**No database migration.** The generated draft reuses `agreement.draft_pdf_key` + object storage. A
new endpoint, one security matcher, and frontend changes. No dependency changes -> no lockfile change.

## Open Questions (resolved)

- **Overwrite vs. version drafts?** Overwrite while unsigned (a draft is a work-in-progress); locked
  once signing starts. Revisioning after a paid eSign attempt is a separate future flow.
- **Response body?** The agreement id only -- never the stored bytes (consistent with the upload
  endpoint).
