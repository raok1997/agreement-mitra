## Context

`agreement-ownership` (CR-B) gave an agreement an owner and a Save/resume/edit surface, but the
aggregate still persists only the **fixed rental columns** (property, rent, deposit, dates, parties).
The guided capture form, however, produces more than that: a user adds **optional sections** and fills
**dynamic template fields**. Those extra values are POSTed to the **stateless** preview endpoint only;
they are never written to the agreement. So on Save they are dropped, the reopened edit form cannot
restore them, and the stored/signed draft (rendered from the aggregate) does not match the preview.

**Grounding (verified against the code):**
- `frontend/.../views/CaptureForm.vue` -- `flatWorking()` builds a `Record<string,string>` field map;
  `activeSections` is a `string[]` of added optional-section titles. Both are already sent to
  `fetchDocumentPreviewHtml/Pdf` (stateless preview). `buildAgreementInput()` maps ONLY well-known fixed
  keys into the create payload (documented STOPGAP); `NON_PERSISTED_FIELDS` hides fields the aggregate
  cannot round-trip; `prefillFromAgreement()` (added in CR-B) restores only the fixed keys.
- `backend/.../signing/agreement/AgreementDocumentService.render` -- builds the data map via
  `AgreementDocumentMapper.toTemplateData(agreement)` and calls
  `documentProjection.generate(new DocumentProjectionRequest(dimensions, data, null, documentReference))`.
  The **third argument is the active-sections list**, currently `null`. The stateless preview path
  already passes a real sections list, so the projection supports it.
- `backend/.../signing/agreement/Agreement.java` -- already stores a `jsonb` value
  (`template_layer_versions` as `Map<String,Integer>` via `@JdbcTypeCode(SqlTypes.JSON)`), the exact
  pattern to copy for the capture blob; server-managed setters, defensive copy.
- `backend/.../signing/api/CreateAgreementRequest.java` / `AgreementResponse.java` -- the request/response
  records CR-B's create + edit + read already use.
- Latest migration after CR-B is `V12`; this change is **`V13`**.

Constraints (unchanged): Java 21 + Spring Boot 3.5.x + Spring Modulith; records for DTOs; constructor
injection; package-private by default; Flyway single source (`ddl-auto: validate`); keep
`ModularityTests` green; sandbox + dummy data only; anti-mass-assignment for server-managed fields;
**preview/draft parity is a hard invariant**.

## Goals / Non-Goals

**Goals:**
- Persist the agreement's **full capture state** (working-set field map + added optional sections) so a
  saved agreement round-trips its complete content.
- Render generate-as-draft (and the on-demand preview of a persisted agreement) from that stored capture
  state, so the **stored/signed draft matches what the user saw in preview**.
- Restore optional sections + dynamic values when an owned agreement is reopened for edit.
- Keep existing agreements (null capture state) rendering exactly as today (fallback).
- Unit + integration test coverage; keep the Modulith boundary and the parity invariant intact.

**Non-Goals:**
- Normalized per-attribute columns / queryable attributes (a jsonb blob suffices).
- Changing effective-template resolution or the reproducibility pin.
- Anything in ownership/authZ or the signing FSM.

## Decisions

### D1: Capture state is a server-managed jsonb blob on the aggregate, held as plain values

`agreement` gains a nullable `capture_state jsonb` column mapped as a small value object
`CaptureState(Map<String,String> data, List<String> activeSections)` (or two jsonb-backed fields). It is
held as **plain JDK types** (a String map + a String list), so the `signing` aggregate carries no
`documents`-module type -- Modulith stays clean, mirroring `template_layer_versions`. Setter is
server-managed and **defensively copies**. **Alternative rejected:** a separate `agreement_attribute`
child table -- a normalized schema is unnecessary to round-trip and render a form blob, and adds
join/migration cost; revisit only if individual attributes must be queried.

### D2: The capture map is user content, validated at render -- not a trusted server field

`captureData` / `activeSections` are **optional** request fields on `CreateAgreementRequest`, accepted on
create and edit. They are **user content**, so the anti-mass-assignment guarantee is preserved a
different way than for scalars: the server still **ignores** any server-managed key (id, owner,
createdAt, duration, template pin) and never derives those from the map. The map's correctness is
enforced where it already is for the preview -- at **render**, by the `documents` projection's
**server-side validation over the effective template's field schema** (unknown key, bad enum, oversize
-> rejected). So a malicious/oversized map cannot corrupt state; at worst it fails the render with the
same 4xx the preview returns. **Alternative rejected:** duplicating the template-schema validation in
`signing` at write time -- it belongs to `documents` (the schema authority) and already runs at render;
duplicating it couples the modules and risks drift.

### D3: Render reads the stored capture state; null falls back to today's mapper

`AgreementDocumentService.render` becomes: if the agreement has a `capture_state`, build the data map
from its `data` (a **superset** of the fixed columns -- the fixed rental values are still authoritative
and take precedence so the two can never disagree) and pass its `activeSections` as the projection's
sections argument; **else** fall back to `AgreementDocumentMapper.toTemplateData(agreement)` with `null`
sections (today's behaviour). This keeps **existing agreements unchanged** and makes generate-as-draft
render **the same content the preview showed** -- closing the parity gap rather than widening it. The
fixed columns remain the source of truth for property/rent/deposit/dates/parties (the capture `data` is
reconciled to them so an edit that changes a fixed field via the typed path is never overridden by a
stale map entry). **Alternative rejected:** rendering purely from the blob -- the typed columns must stay
authoritative for the fields the signing/stamping flow reads.

### D4: Wholesale replace on edit; clear-with-draft semantics unchanged

On `PUT` (CR-B edit) the capture state is **replaced wholesale** (like the party list), and the pinned
draft is still cleared so the next generate rebuilds from the edited capture state. On create it is set
once. **Alternative rejected:** field-level merge of the blob -- the form is submitted whole; a wholesale
replace matches the "reopen the form, resubmit" UX and avoids partial-merge edge cases.

### D5: Frontend retires the STOPGAP; keeps only genuine template-default hides

`CaptureForm.vue` sends `captureData` (= `flatWorking()`) + `activeSections` on create and edit, and
`prefillFromAgreement` restores both from `AgreementResponse` (rehydrating the working set and
re-activating the stored optional sections). The `NON_PERSISTED_FIELDS` hide-list is reduced to only
fields that are **genuinely system-owned template defaults** and never user-set (e.g. `stampDuty`
rendered from the template default); everything the aggregate now persists is un-hidden. **Alternative
rejected:** keeping the hide-list as-is -- it exists only because those fields could not round-trip;
once they persist, hiding them would re-introduce the very data loss this change removes.

## Risks / Trade-offs

- **Parity now depends on render-from-blob.** The stored/signed draft renders from `capture_state`; a bug
  there could diverge from preview. Mitigated by an integration test that generates a draft and asserts
  the added optional section + a dynamic value appear (the parity assertion moves from "hidden" to
  "verified").
- **Unvalidated-at-write blob.** The map is validated only at render (D2). Accepted: it mirrors the
  preview contract exactly, and a bad map fails the render with a 4xx, never corrupts state. A stored blob
  that a later template revision no longer accepts fails the render loudly (same as any schema drift the
  pin already guards).
- **jsonb size.** A capture blob is small (a form's worth of short strings). No practical size concern at
  this scale; the render already caps the working set.
- **Ordering dependency on CR-B.** `V13` follows `V12`; the request/response records extended here are
  CR-B's. Documented in the Migration Plan.

## Migration Plan

Forward-only Flyway `V13__agreement_capture_state.sql` after CR-B's `V12`: one nullable `jsonb` column on
`agreement`. **No backfill** -- existing rows keep `capture_state = NULL` and render via the D3 fallback,
so they are unaffected. Rollback in this sandbox phase: drop the column and remove `V13` (`flyway.clean`
stays disabled). No deployed consumers.

## Open Questions

- **Should `activeSections` be validated against the effective template's optional-section catalog at
  write time**, or is render-time reconciliation (drop unknown titles, as the client already does on
  draft-load) sufficient? Proposing render-time reconciliation for parity with the existing client
  behaviour; confirm.
- **Retire vs shrink `NON_PERSISTED_FIELDS`** -- confirm which fields (if any) remain genuinely
  template-default-only after this change (candidate: `stampDuty`).
