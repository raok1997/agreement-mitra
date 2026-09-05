## Why

CR-2 (`document-projection-render`) made generate-as-draft **definition-driven** but **unpinned**: the
draft PDF is produced from the effective template, yet nothing records **which** effective template
(content + layer versions) drew it. A generated draft is the thing that gets stamped and signed, so it
**must** be reproducible -- a signed agreement can never be silently re-rendered against newer layers
(the exploration's non-negotiable).

This change -- **third of four** superseding `template-document-projection` (design **D7**, **D10**) --
adds the **reproducibility pin**: at generate-as-draft the projection returns the effective template's
identity and the `agreement` aggregate records it via a server-managed
`pinEffectiveTemplate(contentHash, layerVersions)`. It reuses the existing generate-as-draft transition
(no new FSM state) and adds a forward-only Flyway migration for the two pin columns.

## What Changes

- **Reproducibility pin (final commit only):** rewire `POST /api/agreements/{id}/document`
  (generate-as-draft) to render with the projection API in **generate** mode (full validation -->
  parity PDF), store it as the draft through the existing `attachDraft` path, and then **pin** the
  effective template's identity onto the `agreement` aggregate: `template_content_hash` and
  `template_layer_versions`. The pin is server-managed (never client-settable) and set only after a
  successful full render + `attachDraft`. **Previews pin nothing.**
- **Migration:** add forward-only Flyway `V9__agreement_template_pin.sql` adding two **nullable**
  columns to `agreement` (`template_content_hash TEXT`, `template_layer_versions JSONB`). It does
  **not** re-add `template_id` -- V8 (`template_catalog`) already added that (nullable UUID) and its
  header explicitly reserves the remaining pin columns for this CR at **V9**. JPA stays `ddl-auto:
  validate`; map the two columns on the `Agreement` entity.
- **No new FSM state:** the existing generate-as-draft milestone (`DRAFT -> PDF_GENERATED`) is reused;
  the draft-freeze `409` (a signing request already exists) is unchanged.

**Explicitly not in this change:** enforcing pin **immutability after signing** beyond the existing
draft-freeze `409` (rides the eSign CR); the frontend Save & continue wiring (**CR-4**).

## Capabilities

### Modified Capabilities

- `agreement-management` (the `agreement` aggregate): gains a server-managed **effective-template
  pin** -- `template_content_hash`, `template_layer_versions` -- set only at generate-as-draft (never
  client-settable), so the generated draft is reproducible. No existing field or transition changes;
  the aggregate stays status-less.

## Impact

- **`signing` module**: `AgreementDocumentService` (or the generate-as-draft orchestration) calls the
  `documents` `DocumentProjectionApi.generate(...)` (full validation) and, after `attachDraft`, invokes
  a new server-managed `Agreement.pinEffectiveTemplate(contentHash, layerVersions)`. The `Agreement`
  entity gains the two mapped columns. `signing` still depends only on the `documents` public interface.
- **Data / schema**: one forward-only Flyway migration `V9__agreement_template_pin.sql` (two nullable
  columns on `agreement`; never edits V1--V8). JPA stays `ddl-auto: validate`. PDF blobs stay in object
  storage, never Postgres.
- **Dependencies**: **none added.** **No `gradle.lockfile` change.**
- **No** change to: the signing-status FSM, `EsignProvider` / webhook flow, stamping, the
  reconciliation job, the draft-storage path, or any HTTP contract other than the generate-as-draft
  side effect.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** The pin records
  **system-owned metadata** only -- a content hash and a `layerId -> version` map. No party PII, no
  Aadhaar/OTP/VID, no secret is added, stored, or logged by the pin.
- **How redacted/secured?** The pin is server-managed and never client-settable (set only via
  `pinEffectiveTemplate` after a successful full render + `attachDraft`). It contains no PII; the
  rendered PDF continues to go to object storage (never Postgres), and no rendered content or submitted
  value is logged (preserved from CR-2).
- **Sandbox + dummy data only?** Preserved -- dummy reference definition/layers; the pin records their
  dummy identity.
- **Signing-status FSM transitions touched?** **None invented or changed.** This CR makes the existing
  generate-as-draft step record the pin; it reuses that transition and touches no signing-request FSM
  state. The draft-freeze `409` is unchanged.
- **Async signing / webhook flow touched?** **None** -- this CR stops at a pinned, signable PDF stored
  as the draft.
