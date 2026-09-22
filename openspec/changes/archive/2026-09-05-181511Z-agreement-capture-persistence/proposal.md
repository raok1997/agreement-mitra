## Why

The guided capture flow lets a user add **optional sections** (pets, lock-in, ...) and fill
**dynamic template fields** (e.g. `lockInMonths`, `petAllowed`) on top of the fixed rental terms. But
today those optional/dynamic values live **only in the live preview**: the SPA POSTs the flat
working-set data map plus the added `activeSections` to the **stateless** render endpoint
(`POST /api/templates/document/preview`), and **nothing about them is written to the `agreement`
aggregate**. Create and edit persist only the fixed columns (property, rent, deposit, dates, parties)
through `CreateAgreementRequest`; generate-as-draft (`AgreementDocumentService.render` ->
`DocumentProjectionApi.generate`) and the `agreement-ownership` edit-reload render **purely from those
fixed columns** via `AgreementDocumentMapper.toTemplateData`, passing `null` for sections.

Consequences the user sees:
- **Save drops content.** Optional sections and dynamic field values a user added are **silently lost**
  on Save. They never appear when the agreement is reopened from "My Agreements" (edit-reload restores
  fixed fields + parties only), and they are **absent from the stored/signed draft PDF** -- a
  preview/draft divergence the team has so far contained with a client-side `NON_PERSISTED_FIELDS`
  hide-list and a documented STOPGAP (flow-journal 8.4/8.5).

This change closes that gap: it **persists the agreement's full capture state** so a saved agreement
**round-trips its complete content**, and so the stored/signed draft renders **exactly what the user
saw in preview**. This is the parked M5 "attributes store".

It builds on `agreement-ownership` (CR-B): it extends the same create/edit request + response and the
same generate-as-draft path. Latest migration after CR-B is `V12`; this change is **`V13`**.

## What Changes

- Add a nullable, server-managed **`capture_state` (jsonb)** column to `agreement` holding
  `{ data: Map<String,String>, activeSections: List<String> }` -- the flat working-set field map the
  preview already uses plus the added optional-section titles. Held on the aggregate as a plain value
  (no `documents`-module type), mirroring how `template_layer_versions` is stored; server-managed and
  never partially trusted (validated at render, see Design).
- Extend the create + edit request (`CreateAgreementRequest`) with **optional** `captureData` and
  `activeSections`. They remain subject to anti-mass-assignment for server-managed fields (id, owner,
  createdAt, duration, template pin are still never client-settable); the capture map is **user content**,
  validated against the effective template's field schema at render time (the same validation the
  stateless preview already performs), not blindly trusted.
- Store the capture state on **create** and on the CR-B **edit** (`PUT`), replacing it wholesale on edit
  (consistent with the wholesale party replace). A successful edit still clears the pinned draft.
- Return `captureData` + `activeSections` on **`AgreementResponse`** so the capture form can **restore
  the optional sections and dynamic values** when an agreement is reopened for edit from "My Agreements".
- Make **generate-as-draft and preview render from the stored capture state**:
  `AgreementDocumentService.render` feeds the stored `data` map (a superset of the fixed columns) and the
  stored `activeSections` into `DocumentProjectionRequest` (its sections argument is already there --
  today passed `null`), so the stored/signed draft matches the preview. When `capture_state` is null
  (legacy rows / API clients that send only fixed fields) it falls back to today's
  `AgreementDocumentMapper.toTemplateData` with no sections -- **behaviour is unchanged for existing
  agreements**.
- Add Flyway **`V13__agreement_capture_state.sql`**: `alter table agreement add column capture_state
  jsonb` (nullable). Forward-only, after CR-B's `V12`; no backfill (existing rows stay null and use the
  fallback render).
- Frontend: replace the STOPGAP in `CaptureForm.vue`. `buildAgreementInput` sends `captureData`
  (the flat working set) + `activeSections`; `prefillFromAgreement` restores them (working set +
  added optional sections) from `AgreementResponse`. Retire the `NON_PERSISTED_FIELDS` hide-list for the
  now-persisted fields (keeping only genuinely template-default fields such as `stampDuty` that are
  system-owned and never user-set).

**Out of scope (deferred, tracked):**
- Typed, per-field columns for dynamic attributes (a jsonb blob is enough to round-trip and render; a
  normalized attribute schema is a later optimization if querying individual attributes is ever needed).
- Any change to the effective-template resolution / pin mechanics (unchanged; the pin still records which
  template drew the stored draft).
- Ownership/authZ and the signing FSM (untouched -- owned by `agreement-ownership` and the signing slice).

## Capabilities

### Modified Capabilities
- `agreement-management`: an agreement SHALL persist its **full capture state** (the working-set field
  map + added optional sections), accepted on create and edit and returned on read, so a saved agreement
  round-trips its complete content. The stored/signed draft SHALL render from that persisted capture
  state so it matches the live preview. Fixed-column behaviour, validation, ownership, and
  anti-mass-assignment for server-managed fields are otherwise unchanged.

## Signing-status FSM

**No new states and no changed transitions.** The `SignatureStatus` FSM is untouched and still lives on
`SigningRequest`. Persisting the capture state is orthogonal to the FSM; generate-as-draft still uses the
existing generate step (no new status). Editing remains permitted only in the pre-signing-request window
(the existing draft-freeze), unchanged from `agreement-ownership`.

## PII / security checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **No new PII category and no new secret.** The
  capture map is the same party/terms content the user already types into the create body and the preview
  (names, addresses, amounts, optional-section values); this change persists that content on the
  agreement instead of discarding it. No Aadhaar/OTP/VID; no external flow; no new secret.
- **How redacted/secured?**
  - The capture map is **user content**, not a server-managed field: it is **validated at render** against
    the effective template's field schema (the same server-side validation the stateless preview runs),
    so an unknown/oversized/ill-typed key is rejected -- it is never rendered or trusted blindly.
  - Server-managed fields (id, `owner_identity_id`, `createdAt`, derived duration, template pin) stay
    **anti-mass-assignment** -- the capture map cannot smuggle them in (they are ignored keys).
  - The stored blob holds no new PII category and is **never logged** (the existing
    never-log-signer-PII discipline covers it; render bytes are already never logged).
  - Access is unchanged: the capture state is returned only through the **owner-scoped** read/edit from
    `agreement-ownership`.
- **Sandbox + dummy data only?** Preserved -- dummy data only; no new external flow.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no signing sequence diagram required.
