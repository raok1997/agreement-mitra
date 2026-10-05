## Context

See `proposal.md` for motivation and `docs/BYO-DOCUMENT-UPLOAD.md` for the
direction, the rejected alternatives and the mock screens. The requirements are in
this change's `specs/`.

What the code actually looks like today, because three of these facts contradict
the assumptions the direction doc was written under:

- **An agreement has no `state` or `type` column.** Both are derived from
  `template_id` through the catalog, and `AgreementService.toResponse` returns
  `null` for each when no template is pinned. So a BYO agreement has nowhere to
  say which state it is stamped in.
- **A null `template_id` does not mean "no template" today.** `state`/`type` are
  optional on `POST /api/agreements`, and when either is blank the agreement gets
  no `template_id` and `documents` falls back to
  `DocumentProjectionService.DEFAULT_DIMENSIONS` (`IN`/`residential`). Null
  `template_id` currently means "templated, with the default", so it cannot be
  overloaded to mean BYO.
- **There is no entry point that renders only a signature block.**
  `TemplateCompiler.compile` always emits a full document (doctype, head, header,
  every gated section, provenance, notice); `appendSignatures` is `private
  static`; and `DocumentProjectionApi` exposes only whole-document methods whose
  GENERATE mode validates the template's entire required-field set. "Render the
  execution page through the existing `render: signatures` path" is therefore not
  free.
- **Gotenberg paper size is hardcoded A4** (`A4_WIDTH_IN`/`A4_HEIGHT_IN` in
  `GotenbergClient`), with `FURNITURE_MARGIN_IN = 0.6` reserving the footer band.
- `DraftService.attachDraft(UUID, byte[])` runs validate -> freeze-check -> store
  -> attach, validates only non-null, length >= 5 and the `%PDF-` magic bytes, and
  clears `draft_execution_date`. Multipart limits (10 MB file / 11 MB request)
  come from `application.yml`, not from the service.
- Per-signer placements are built in `SigningRequestService.buildSignRequest` as
  an anchored block plus a vendor-neutral `everyPageFooter()` strip; the strip
  carries no anchor. `EsignAnchorLocator.geometry` measures the **smallest** page
  and falls back to `width*0.1 / width*0.9` for a document with no text layer.
- A missing anchor is refused in `ZoopEsignProvider` by throwing
  `IllegalStateException`, which **has no `@ExceptionHandler`** and therefore
  surfaces as an unmapped **500**. The clean `409 not-signable` pre-flight in
  `SigningRequestService` inspects signer *roles*, not the PDF.

## Goals / Non-Goals

**Goals:**

- One signable-draft shape downstream: after composition, stamping and signing
  treat a BYO draft exactly as they treat a generated one.
- Anchor-token derivation stays single-source: the execution page's anchors come
  from the same renderer that produces a templated document's.
- The duty quote for a BYO agreement runs through the existing calculator with no
  second fact-mapping path.
- Composition is recomputable: a later party change must be able to rebuild the
  execution page.
- A Word upload is accepted without adding infrastructure, and what the customer
  approves at review is exactly what gets signed.

**Non-Goals:**

- Footer-band detection, disclosure UI and per-page strips for BYO
  (`byo-every-page-signatures`).
- Reserving the strip band in the certificate page (landed 2026-10-05 as a direct
  fix to `PdfStampComposer`; not a prerequisite here because BYO places no strips).
- Any change to the async signing/webhook flow, the signing FSM, or the payment
  gate.
- Legacy `.doc` and `.odt` ingestion (D11), and drag-to-place signature
  positioning.

## Decisions

### D1 -- The `/start` entry point is a picker pseudo-card, not a catalog row

`TemplatePicker.vue` renders an "Upload your own document" card alongside the
catalog cards and emits a BYO start instead of a `(state, type)` selection.

*Alternative rejected: seed a `template` row for BYO.* `TemplateCatalogEntry`
requires `layer_set_ref`, `version` and `status` NOT NULL, and every read path
resolves that pointer to a layer set on the classpath. A row whose layer set
cannot resolve would be returned by `GET /api/templates`, be selectable through
`publishedTemplateIdFor`, and fail at resolution -- a broken template visible in
the catalog, to buy one card.

### D2 -- Three new columns, because BYO cannot be inferred

Migration **`V22__byo_document.sql`** (next free number; highest applied is `V21`)
adds to `agreement`:

| column | type | null | why |
|---|---|---|---|
| `document_source` | `TEXT` NOT NULL default `'TEMPLATE'` | no | The explicit marker. Per the Context, `template_id IS NULL` already means "templated with the default", so it cannot carry this. |
| `duty_state` | `TEXT` | yes | The state the property lies in. There is no `state` column to reuse, and eligibility keys on this. |
| `upload_content_hash` | `TEXT` | yes | The integrity record that replaces the template pin. |

All three are server-managed and rejected from any client body, like the existing
pin columns. The default on `document_source` is what keeps every existing row
correct without a data migration.

*Alternative rejected: infer BYO from `template_id IS NULL`* -- see Context; it
would reclassify existing default-template agreements as BYO.

### D3 -- Declared duty facts ride in `capture_state`, not in new columns

`capture_state` is already an opaque `jsonb` map that `POST`/`PUT` accept as
`captureData`, and `DutyBasisMapper` already reads the duty facts out of it. The
declaration therefore needs **no** schema work beyond `duty_state`: instrument
kind, usage, escalation, rent-free months, advance rent, premium and counterparts
go in the same map the templated flow fills. `duty_state` is the one exception
because eligibility and the frozen quote key on it and it must not be a free-text
map key.

*Alternative rejected: normalized columns per fact.* That is already a deliberate
open trade-off (`capture-state-normalized-columns` in the register); this change
should not silently decide it.

Note the sharp edge this inherits: `stamp-quote-capture-defaults` (register, High
for long terms) means a fact the customer never touched reads as absent rather
than as the template default. BYO has no template default, so for BYO an absent
fact must be **required in the declaration** rather than defaulted -- which is the
stricter, safer side of that open bug.

### D4 -- The execution page is its own minimal layer set, rendered through the existing compiler

Add a layer set (for example `documents/template/sets/execution-page/base.yaml`)
whose only section is `render: signatures` with `entries: [ownerName, tenantName]`,
and expose it as a new `documents` module API method -- roughly
`generateExecutionPage(parties, pageSize)`.

This reuses `appendSignatures`, the `.sign-anchor` white-on-white CSS (8px,
`#ffffff`, never `display:none`, or the glyphs leave the text layer and every
signing request is refused) and `anchorRole` derivation with **no change to the
compiler**, and it keeps the module boundary intact: `signing` calls `documents`
through `DocumentProjectionApi` and holds no `documents` internal type. The set is
not seeded into the catalog, so D1 holds.

*Alternatives rejected:* making `appendSignatures` public or adding a
section-level compiler entry point (a second document shape inside the compiler,
and a public surface that invites more of them); building the page with PDFBox
directly (would duplicate both the anchor-token derivation and the
invisible-but-extractable text trick -- precisely the drift the direction doc
warns about).

### D5 -- Gotenberg paper size becomes a parameter

`renderHtml` gains a page size, defaulting to today's A4 constants so every
existing caller is unchanged. The execution page is rendered at the uploaded
document's first-page size (spec: "The execution page SHALL match the uploaded
document's page size"), because `EsignAnchorLocator.geometry` takes the
**smallest** page across the document -- an appended page smaller than the
customer's pages would drag placements inward for the whole instrument.

The `0.6in` footer furniture stays on the appended page: it keeps the reference
footer consistent with a generated document and keeps the strip band clear for
CR-3, which will place strips on this page even when it cannot place them on the
customer's.

### D6 -- Both the original upload and the composed draft are stored

- `uploads/{agreementId}.{pdf,docx}` -- the customer's bytes, exactly as received
  (see D11 for the converted-PDF key a Word upload adds).
- `drafts/{agreementId}.pdf` -- the composed document (customer pages + execution
  page), which is the signable draft and the only thing downstream reads.

The content hash is of the **uploaded** bytes, because the question it answers is
"is this the customer's document?".

Retaining the original is what makes two things possible: verifying the hash
against stored bytes later, and **recomposing** when the party list changes (a
renamed or added party changes the execution page, and without the original there
is nothing to append to). Cost is one extra blob per BYO agreement, under the same
10 MiB ceiling.

*Alternative rejected: keep only the composed draft* -- a party edit would then
either be refused or would have to re-derive the customer's pages by stripping the
last page of the composed file, which is fragile and irreversible if it is wrong.

### D7 -- Declaration first, then upload, then review

The flow is: BYO card -> declare (facts + parties) -> upload -> review composed
document -> contacts -> stamp quote -> pay. Composition needs the party list, so
the agreement is created through the existing `POST /api/agreements` (with
`captureData` and `document_source = UPLOAD`) before the upload lands.

A party change afterwards recomposes from the retained original (D6) while the
draft is still replaceable, and is refused once a signing request exists -- the
existing `draft-frozen` 409, unchanged.

### D8 -- Block-only is decided in `buildSignRequest`, not in an adapter

`SigningRequestService.buildSignRequest` emits the anchored block placement only
when the agreement's `document_source` is `UPLOAD`, and both placements otherwise.
The strip is vendor-neutral (`SignRequest.Placement.everyPageFooter()`), so
deciding this above the adapter keeps every provider consistent and keeps the
choice out of `ZoopEsignProvider`.

### D9 -- The missing-anchor refusal gets a mapped status, in this change

Today a missing anchor throws `IllegalStateException` from `ZoopEsignProvider`
with no handler, so the caller sees a **500**. Under BYO, the appended page is the
*only* thing that puts anchors in the document, which makes that path materially
more likely than it is for a templated render. Add an `@ExceptionHandler` mapping
it to a distinct problem type (`422`, alongside `stamp-failed`) so a composition
defect surfaces as a diagnosable client error rather than an opaque server error.
This improves the templated path too; it is folded in because this change is what
makes it probable.

### D10 -- Upload bounds, and where they are enforced

`DraftService` gains a parse step (PDFBox `Loader.loadPDF`, the same library
`PdfStampComposer` already parses drafts with), applied to the PDF as uploaded or
as converted (D11), that rejects, as `400`
`invalid-upload` with no parser detail echoed: unparseable bytes, an encrypted or
password-protected document, zero pages, more than **50** pages, and any page
whose width or height falls outside **200pt to 1684pt** (roughly A6 to A2, which
covers every realistic agreement while refusing the absurd). The 10 MiB multipart
ceiling stays where it is, in `application.yml`.

The numbers are config-backed so they can be tuned without a spec change; the
spec states only that bounds exist and that breaching them fails closed.

### D11 -- Word is converted by the Gotenberg we already run, not by a new service

`docs/BYO-DOCUMENT-UPLOAD.md` rules `.docx` out on two grounds. Both have
dissolved:

- *"Needs a LibreOffice-headless service beside Gotenberg."* It does not. Our
  image is `gotenberg/gotenberg:8`, which bundles LibreOffice alongside Chromium,
  and the route is live on the container we already run: `POST
  /forms/libreoffice/convert` answers `415` on a wrong content type rather than
  `404`. So this is a second call to an existing dependency, not new
  infrastructure.
- *"Contradicts the never-parse invariant."* That invariant is being modified by
  this change regardless (D10, and the `draft-ingestion` delta).

So the upload accepts PDF or `.docx`, a Word upload is converted on receipt, and
the **converted PDF is what is composed, reviewed, stamped and signed**. Format is
decided on content -- `%PDF-` for PDF, and for Word the ZIP signature *plus*
confirmation that the archive really carries an OOXML word-processing document, so
an arbitrary ZIP is refused rather than handed to LibreOffice.

Blob keys become three for a Word upload, two for a PDF:

| key | contents |
|---|---|
| `uploads/{agreementId}.{pdf,docx}` | the customer's bytes as received; the hash is over these |
| `converted/{agreementId}.pdf` | the conversion (Word uploads only) |
| `drafts/{agreementId}.pdf` | the composed signable draft, the only thing downstream reads |

The conversion is retained rather than re-derived because re-converting is not
guaranteed to reproduce the same bytes (fonts and LibreOffice version both move),
and the recompose-on-party-change path (D6) must append to the *same* pages the
customer approved.

**The consequence that does not dissolve: the signed instrument is a rendering of
the customer's file, not the file itself.** A converted document can paginate
differently from what the customer saw in Word. The mitigation is structural
rather than best-effort -- the review step (D7) shows the *converted* document
before any payment, so what they approve is what gets signed. This is why review
is not optional for BYO and why conversion happens at upload rather than at
finalise.

Two supporting decisions follow:

- **Fonts.** Our Gotenberg image installs only `fonts-noto-core`, so a Word file
  naming Calibri, Cambria, Arial or Times New Roman font-substitutes and reflows.
  Add the **metric-compatible** families -- `fonts-crosextra-carlito` (Calibri),
  `fonts-crosextra-caladea` (Cambria) and `fonts-liberation` (Arial, Times New
  Roman, Courier New) -- so substitution preserves line and page breaks instead of
  merely approximating the glyphs. Metric compatibility is the whole point: a
  visually similar font with different metrics still reflows the document.
- **Network posture.** `CHROMIUM_DENY_PUBLIC_IPS` / `CHROMIUM_DENY_PRIVATE_IPS`
  are Chromium-scoped and do **not** constrain the LibreOffice route. A `.docx`
  can carry linked images and other external references, so the conversion path
  must be denied outbound network before this ships. Verify what the route
  actually does with a linked reference and pin it down at the container or
  network level; do not assume the Chromium guard covers it.

*Alternative rejected: convert in-process with a Java library* (docx4j, POI +
a renderer). Worse on every axis that matters here -- it parses attacker-supplied
archives **inside our JVM** rather than in a separate container, adds a large
dependency to the OSV-scanned graph, and produces lower-fidelity output than
LibreOffice for the exact layout-preservation reason above.

*Alternative rejected: accept `.doc` and `.odt` in the same change.* LibreOffice
handles both, so each is a configuration widening, but each is also more parser
surface reached from a `permitAll` endpoint for no customer we have today. They
are a follow-on once the security posture above is settled.

## Risks / Trade-offs

- **A BYO document ends up with two signature blocks** (the customer's own, then
  ours) -> the appended page is labelled as a system-appended execution page.
  Accepted and recorded in the direction doc; the alternative is placing
  signatures on text we did not draft.
- **BYO output is weaker at a counter than templated output** for one release (no
  per-page marks) -> disclosed before payment, and CR-3 upgrades it without
  changing anything already signed.
- **A wrong declaration under-stamps the instrument** -> the declaration is the
  customer's own attestation, recorded and auditable, and this is the same hazard
  `jurisdiction-checkout-gating` closes for templated agreements. Counsel review
  of the declaration copy rides with the ToS delta.
- **A pure-scan upload has no text layer**, so `geometry`'s body-column
  measurement falls back to page proportions -> harmless here precisely because
  BYO is block-only: the anchors live on *our* rendered page, which always has a
  text layer. This is an argument for block-only, not a risk of it.
- **Mixed page sizes in one document** (A4 certificate page + customer pages) ->
  already the normal case at stamp intake; `geometry`'s smallest-page logic
  absorbs it, and D5 keeps the appended page from making it worse.
- **`signing-auth` may not have landed** -> then this change carries ownership
  authZ and a rate limit on `/api/agreements/*/draft` and the preview fetch only,
  and the register row stays open recording the partial. A public upload endpoint
  that parses attacker-supplied PDFs is a materially worse exposure than a public
  upload endpoint that only stores them, so this is the one prerequisite not to
  skip quietly.
- **Word conversion is the largest new attack surface in this change** ->
  LibreOffice parsing an attacker-supplied archive is wider than PDFBox parsing a
  PDF, and it is reached from an endpoint that is `permitAll` today. Mitigated by
  running it in the Gotenberg container rather than the JVM, by refusing any ZIP
  that is not genuinely an OOXML word-processing document, by the 10 MiB ceiling,
  and by the outbound-network denial in D11 -- which is a **task, not an
  assumption**. This raises the cost of `signing-auth` not having landed.
- **A converted document reflows** -> metric-compatible fonts (D11) plus a review
  step that shows the conversion, not the upload. A customer whose layout matters
  more than convenience can always export to PDF themselves, and the upload step
  should say so.
- **Parsing untrusted PDFs in-process** -> bounded by the existing 10 MiB ceiling
  plus D10's page and dimension caps, fails closed, echoes no parser detail, and
  runs on bytes the system already parses downstream. PDFBox stays the only parser
  so there is one library to patch, and it is already in the OSV-scanned locked
  graph.

## Migration Plan

1. `V22__byo_document.sql` -- forward-only, additive, all three columns
   nullable-or-defaulted, so it applies to a live database with no backfill and
   JPA stays `ddl-auto: validate`.
2. Ship the backend (compose, bounds, block-only placement, mapped refusal, the
   preview branch) with no UI entry point: nothing can reach it because
   `document_source` is `TEMPLATE` for every existing and API-created agreement.
3. Rebuild the Gotenberg image with the metric-compatible fonts and apply the
   conversion route's network posture (D11). This is infrastructure, so it lands
   before the UI that can reach it.
4. Ship the frontend card and flow, plus the regenerated terms and the
   `LegalDisclaimer` variant.

**Rollback** is the reverse of step 4: removing the picker card makes the flow
unreachable while every column and code path stays in place. No migration is
reverted -- an additive, defaulted column is inert once nothing writes `UPLOAD`.
Agreements already created as BYO keep working, which is the point: a signed legal
document must not be stranded by a feature rollback.

## Open Questions

1. The exact page-count and page-dimension bounds (D10) -- config-backed, tunable
   without touching the specs.
2. The wording of the execution page's label and of the "signatures appear on the
   appended page" disclosure. Both are customer-facing copy that rides with the
   ToS delta's counsel review; the drafted variants are in the mocks.
3. Whether to warn a customer whose uploaded document already appears to carry a
   signature block. Detecting that needs the same text inspection CR-3 builds, so
   it is naturally that change's to answer.
