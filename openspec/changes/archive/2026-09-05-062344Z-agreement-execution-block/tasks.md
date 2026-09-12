# Tasks -- agreement-execution-block

Template-first: do the clause work as definition edits (S1), extend the engine's existing
`SIGNATURES` render for the signatory zones + anchors and add the render-layer furniture (S2-S4),
map anchors to provider fields in `signing` (S5). Behavioral change -> unit + integration tests (S6).

> **Refinement note (grounded in the current codebase).** The production template lives in
> `backend/src/main/resources/documents/template/sets/rental/` (base.yaml + the TG / residential
> patches), NOT `examples/layers/` -- that is a test-fixture set. `RenderKind.SIGNATURES` and the "In
> Witness Whereof" section (`render: signatures`) ALREADY exist; the compiler renders a generic
> two-line block ([TemplateCompiler.appendSignatures]). The engine work is to EXTEND that render, not
> add a block type. The compiler is a pure function over a flat data map (`ownerName`/`tenantName`),
> so the signatory zones are driven by the section's `entries` (name field keys) with the anchor
> derived from the key -- no definition-schema change.

## 1. Template edits (no code -- `documents/.../sets/rental/`)

- [x] 1.1 Add the national boilerplate clauses to `sets/rental/base.yaml` (`clauses:`) as system-owned
  static clauses: **Notices** (service of notice -- to each party's address), **Governing law**
  (laws of India), **Severability**, **Entire-agreement / Amendment**. Plain clause text, no new
  `{{slots}}` beyond existing party fields. Add their ids to the "Now This Agreement Witnesseth"
  section's `entries` (render: clauses). NOTE: the TG layer already adds a Telangana-specific
  `tgGoverningLaw` in `state-TG.patch.yaml`; keep the national `governingLaw` general so the TG
  overlay reads as the more-specific statute (no contradiction) -- do not duplicate wording.
- [x] 1.2 Author the **"IN WITNESS WHEREOF ..."** closing wording as the intro of the signatures
  render (S2.1) -- it is system-owned markup emitted by the compiler, so the template edit is only
  the section declaration (the section already exists; keep it mandatory as base declares it).
- [x] 1.3 Declare the **signatories** on the existing `render: signatures` section by setting its
  `entries` to the signer name field keys -- `entries: [ ownerName, tenantName ]` (today empty). The
  compiler derives one zone + one `esign:<role>` anchor per entry from the key (S2). Separately add
  the **optional "Witnesses" section** (`optional: true`, opt-in via activeSections like every other
  optional section) with fields `witness1Name` / `witness1Address` / `witness2Name` /
  `witness2Address` (all `required: false`, default empty). Keep the TG / residential patches free of
  execution/witness overrides (state-TG does not touch the signatures section today).

## 2. Compiler: per-signer signature zones + eSign anchors (`documents` engine)

- [x] 2.1 Extend `appendSignatures` in `TemplateCompiler.java` (and its `appendSectionBody` dispatch)
  to take the section + `fieldsByKey` + `values`. For each name-field entry render a signature
  **zone** -- signature area, the signer's name value (escaped, from the data map), the "Name (as per
  Aadhaar)" / date / place labels (system-owned). Open with the "IN WITNESS WHEREOF" paragraph. All
  signer values go through the existing `escape(...)` path (markup/data boundary) -- an injected
  `<script>` renders inert.
- [x] 2.2 Emit a **stable, non-PII eSign anchor** per zone as a detectable text token
  `esign:<role>`, with the role derived from the entry's field key (`ownerName` -> `owner`,
  `tenantName` -> `tenant`; strip the `Name` suffix, lower-case). Provider-agnostic (D2): a plain
  text token in the rendered HTML that survives HTML->PDF so a text-anchor provider can locate it. Do
  NOT emit any provider-specific field here. The anchor is part of the effective template -> already
  covered by the version pin (`AgreementDocumentService.pinEffectiveTemplate`), no new pin work (D6).
- [x] 2.3 Add a **witness render** for the optional Witnesses section (reuse `render: keyvalue` or a
  small witness branch): witness name/address as printed **escaped** data, **no anchor** (D3). It
  renders only when the section is opted in (activeSections) -- standard optional-section gating.
- [x] 2.4 Keep `documents` eSign-agnostic and pure: it still receives only the flat data map (field
  key -> value), never a `signing` type; the anchor is a template-derived token, not a provider
  field. `ModularityTests` stays green. Keep the always-appended `SIGNATURE_BLOCK` fallback in sync
  (or leave it -- it fires only for fixtures that declare no signatures section).

## 3. Document furniture (`documents` render layer -- Gotenberg)

- [x] 3.1 Render a unique, non-PII **agreement reference** and **page X of Y** via the Gotenberg
  header/footer (`GotenbergHtmlPdfRenderer` / `GotenbergProperties`), NOT the pure HTML compiler.
  Thread the reference (agreement id / a short ref) through `DocumentProjectionRequest` ->
  `GotenbergHtmlPdfRenderer` as render metadata. Ties the document to the eSign audit trail (D4). The
  reference carries no Aadhaar / OTP / VID / secret.
- [x] 3.2 Leave layout room for the provider-appended **audit-trail annexure** (no render by us);
  document the assumption in `documents/package-info.java` or the renderer javadoc.

## 4. signing: map anchors -> provider signature fields (`signing`, EsignProvider seam)

- [x] 4.1 At create-signing-request, derive each signer's `esign:<role>` anchor from its **role**
  (`Role.OWNER` -> `esign:owner`, `Role.TENANT` -> `esign:tenant`) -- the same deterministic token
  the compiler emits -- and attach it to the invitee. Extend `SignRequest.Invitee` with an
  `esignAnchor` (or a signature-field spec). `signing` holds no `documents.template` type; the anchor
  is a plain role-derived string. (Refinement of D2: `signing` derives the anchor from the roles it
  already holds rather than parsing PDF text -- no PDFBox dependency; the literal PDF-text presence
  read is a possible follow-on guard.)
- [x] 4.2 Map the anchor to the vendor's signature field in `LeegalityEsignProvider` (text-anchor vs
  coordinate is an open question -- see design; the template-side anchor is unchanged either way).
  Exercise against the existing **stub / WireMock** provider (no live credentials).
- [x] 4.3 **Fail clearly when there is nothing signable**: if the signer set yields no anchors (no
  signers), the request fails before any provider call and submits nothing.
- [x] 4.4 Confirm **no new signing-status FSM state**: anchors are produced at `PDF_GENERATED`
  (generate-as-draft) and consumed at `SIGN_REQUESTED` (create-signing-request); existing
  transitions only.

## 5. (removed -- merged into 4)

## 6. Tests (pyramid -- required)

- [x] 6.1 **Unit (documents):** the compiler renders one signature zone per name-field entry; each
  zone emits the expected `esign:<role>` token; signer values are escaped (an injected `<script>`
  renders inert); the optional Witnesses section renders only when opted in, as escaped data with no
  anchor; the boilerplate clauses render in the clause list. No Spring context.
- [x] 6.2 **Unit (signing):** role -> `esign:<role>` anchor derivation is one-per-signer and
  deterministic for a two-party (owner+tenant) agreement; an empty signer set yields no anchors.
- [x] 6.3 **Integration (Testcontainers PG + MinIO + Gotenberg):** generate-as-draft produces a PDF
  whose text **contains the execution block, both eSign anchors, the boilerplate clauses, and the
  agreement reference**; the id-bound preview shows the same block (parity); the pinned
  effective-template hash is unchanged across re-render (reproducibility).
- [x] 6.4 **Integration (signing):** create-signing-request against the stub/WireMock provider maps
  both anchors to signature fields; `ModularityTests` green.

## 7. Verify + wrap-up

- [x] 7.1 Live drive against the running backend (`:8090`, TG agreement): generate-as-draft, confirm
  the rendered PDF now has the execution/signature block + anchors + boilerplate + agreement ref, and
  that the "In Witness Whereof" section is no longer empty.
- [x] 7.2 `spotlessApply`; run the `documents.*` + `signing.*` unit + integration suites +
  `ModularityTests` (Windows: `TESTCONTAINERS_RYUK_DISABLED=true`, `-Duser.timezone=Asia/Kolkata`).
  No new dependency / lockfile change expected (role-derived anchor avoids a PDF parser). No
  migration (no schema change).
- [x] 7.3 Coordination note in the handoff: date/amount/clause-numbering **formatting** and the
  witness/label legal decisions belong to `rental-document-content-v2`; the actual eSign OTP
  integration is the subsequent signing module; TG e-stamp is a separate stamp-provider CR.
