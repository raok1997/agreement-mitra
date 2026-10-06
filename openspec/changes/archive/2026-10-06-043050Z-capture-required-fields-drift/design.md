## Context

Two copies of one rule disagree. The agreement API requires every party's father's/spouse's name and
current address (`CreateAgreementRequest.SignerRequest`, `@NotBlank`). The template sets — which the
capture form reads to decide asterisks and section completeness (`formModel.isSectionComplete` filters on
`f.required`) — declare `ownerFatherName` / `ownerAddress` / `tenantFatherName` / `tenantAddress`
`required: false` in both `sets/rental/base.yaml` and `sets/commercial/base.yaml`. No state or type patch
redeclares them.

The rental spec's parity contract explains why they were left optional: a field may be `required: true`
only if `AgreementDocumentMapper` supplies it from the aggregate (or it has a default), so a
generate-as-draft fed only aggregate keys never trips the GENERATE required-check. The mapper emits eight
keys today. But the aggregate *does* hold these four values — `Signer.fatherName` / `currentAddress`,
`NOT NULL` since `V7` (which backfilled pre-existing rows with `''`) and non-blank on every API
create/edit — the mapper simply never emitted them; they reached the document only through the capture
map.

The client already treats the signer columns as authoritative: on load `CaptureForm.vue` overlays each
signer's `fatherName` / `currentAddress` onto the flat working set, and on save writes the flat keys back
to the signer. The server is the side that does not.

Generate, server preview-by-id, and the stamp re-render all build their data as
`captureMap ∪ mapper` with the mapper winning (`AgreementDocumentService.dataFor`, "fixed columns win").

## Goals / Non-Goals

**Goals:**
- One answer to "are these fields required?" — yes, in the server, the template and therefore the form.
- Keep the parity rule ("required ⇒ aggregate-backed or defaulted") true rather than weakening it.

**Non-Goals:**
- Changing the server validation or its error messages.
- Multi-party rendering — still first owner / first tenant only (mapper design D-C). The edit form's
  prefill takes the *last* signer per role while the mapper takes the *first*; that split already exists
  for names and only shows for multi-party API-created agreements. Not fixed here.
- A length cap on party fields (`@Size` / template `maxLength`) — pre-existing, unchanged by this CR.
- Commercial-specific labelling ("authorised signatory" under a "Father's name is required" server
  message) — for when commercial is re-enabled.
- KA statutory overlays or any other field's required-ness. Any signing-state, eSign, webhook, DTO, or
  migration change.

## Decisions

**D1 — Make the four keys aggregate-backed in the mapper, not just flip the YAML.** Flipping
`required: true` alone would make every agreement without a capture state fail generate even when the
aggregate holds the values. Emitting them from the first signer per role — the same rule
`ownerName`/`tenantName` use — keeps generate-with-aggregate-keys valid and makes the stored party columns
authoritative for these keys, matching what the client already assumes. It also closes an over-binding
gap: today an API client can send a `captureData.ownerFatherName` that differs from the `@NotBlank`-
validated `signers[].fatherName`, and the unvalidated map value reaches the deed. Implementation: one
`firstSignerByRole` helper returning the `Signer`, read for name / father's name / address, so
"first per role" is defined once. Alternative rejected: relax the server rule (product chose mandatory).

**D2 — Change both rental and commercial base sets.** Commercial is hidden for v1, but it uses the same
keys and the server applies the same `@NotBlank`. The commercial requirement in
`template-document-projection` ("every required commercial field is aggregate-backed or defaulted") is
phrased generically and holds after D1 — no delta there.

**D3 — Legacy blank rows: fail loudly, don't default.** A pre-`V7` agreement with no capture state holds
`''` in `father_name` / `current_address`. `SubmittedDataValidator` treats blank as absent, so generate
(and server preview-by-id, which shares `render()`) now fails with `400` + `errors[]` naming the keys
instead of rendering a recital with blanks. `errors[]` carries key + rule token only, never a value. A
system default was rejected: it would silently produce a legally weaker deed. **No such row exists in
production**: `V7` landed 2026-07-13 and the first deploy tooling 2026-08-29, so the production schema
was created with `V7` already applied and every row there went through `@NotBlank`. The case is
dev/sandbox data only. Such rows are also unowned (`V12` did not backfill `owner_identity_id`), so the
repair path is sign in → claim → edit, and one that already has a signing request cannot be edited at
all; validation precedes the freeze check in the controller, so its generate reports `400` rather than
`409`. Both are accepted for dev-only data; the repair path is not tested.

**D4 — Mapper emits the raw value; blanks are the validator's concern.** The mapper puts what the signer
holds — `''` for a legacy row, null only when the role has no signer — like the existing name keys.
Whitespace-only is blank to `@NotBlank`, to the validator and to the client's trim alike.

**D5 — Bump the template versions: rental base 3 → 4, commercial base 2 → 3.** The base file's own rule
is that two materially different deeds must not report one authored version (`pinEffectiveTemplate`
records it). This change alters the composed template (required flags → content hash) and can alter the
rendered deed (signer columns now win over a differing capture value; a blank party now fails). Add a
version-history comment in each file, and update the PARITY CONTRACT header comments, which list the
eight keys. Known limitation, as with v2/v3: `TemplateCatalogSeeder` inserts only missing
`(state, type)` rows, so an already-seeded sandbox catalog keeps showing the old version while new pins
record the new one; the pin, not the catalog row, is the record of what rendered.

**D6 — Stamp re-render: accept the `PIN_DRIFT` fallback for drafts generated before deploy.** Changing
the content hash means `reRenderStoredDraft` no longer matches the pin of any draft rendered before this
change, so stamp intake composites onto the stored draft (`PIN_DRIFT`, logged) instead of re-rendering
it with the paid duty. This is the documented fallback and is what every previous template revision
(v2, v3) also triggered; the stored draft is the deed the parties signed. Drafts generated after deploy
re-render normally. The new party-field required check cannot fail inside `renderForStamp`: a matching
pin means the draft was generated after this change and passed the check; any later `PUT` (allowed only
until a signing request exists, `AgreementService.java:272`) is `@NotBlank` on the parties and clears the
pin (`clearDraftPin`), sending the stamp path to its `NOT_A_RECORDED_RENDER` fallback; and stamp intake
requires a signing request, after which parties are frozen. So no handler is added for it.

## Risks / Trade-offs

- [A stale capture-map value for these keys is now overridden by the signer column] → intended (D1).
- [Legacy un-signed blank-party drafts can no longer be previewed or generated until edited] → D3.
- [In-flight paid orders stamp onto the stored draft without the duty re-render] → D6, accepted.
- [The key list is copied in the mapper javadoc, the layer-set test helpers and two YAML headers] →
  mitigated by a guard test asserting the mapper's key set covers every required-without-default field
  the served production form schemas declare, so the next required field the mapper does not supply
  fails one test instead of drifting silently. It must read the schema through the public
  `TemplateFormApi.formFor` under the `test,sandbox` profiles (the plain `test` profile resolves the
  fixture set, and `TemplateResolver` is package-private to `documents.template`), so it is a
  Docker-gated integration test in `signing.agreement`.
- [Plain-`test`-profile integration suites resolve the example fixture set, which has no party father's
  name / address fields] → every integration task for this change lives in a `test,sandbox` suite.
