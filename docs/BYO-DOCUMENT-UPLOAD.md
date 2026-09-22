# Bring-your-own document — agreed direction

**Status:** direction agreed, **not yet proposed**. No `openspec/changes/` entry exists
for any of it. Scheduling lives in [ROADMAP.md](ROADMAP.md) Track A.
**Origin:** design session 2026-09-11/12.
**Related:** [PRODUCT-FEATURE-SET.md](PRODUCT-FEATURE-SET.md) ·
[LEGAL-POSTURE.md](LEGAL-POSTURE.md) · `openspec/specs/draft-ingestion/` ·
`openspec/specs/esign-signature-placement/`

**Mock screens:** https://claude.ai/code/artifact/1db9e70f-3f6d-4757-859c-174609d69360
(nine artboards: the flow, the appended page at actual A4 size, the states sheet, the
upload step at 390px. Private — ask for access if the link refuses.)

The customer picks a template today and cannot change a word of it. This is the agreed
answer: let them upload their own PDF instead and carry it through the existing stamp +
Aadhaar eSign flow unchanged.

---

## 1. Why this, and not user-authored templates

Both were on the table. Upload-your-own goes first, and the deciding argument is exposure,
not effort:

- **A template-authoring tool increases our authorship exposure; BYO reduces it.** Every
  agreement pins a `templateContentHash` + `layerVersions`, and
  `template-counsel-signoff-gate` is an open **High** register row recording that *nothing
  prevents unreviewed template content from shipping today*. Letting customers author
  content that renders through our pipeline, under our provenance line, multiplies exactly
  that problem. With BYO we did not draft the instrument.
- **It is already on the product plan.** [PRODUCT-FEATURE-SET.md](PRODUCT-FEATURE-SET.md)
  tiers "Upload-your-own-draft" as **MVP, near-table-stakes**; NoBroker and AgreementKart
  both offer it.
- Custom templates also fight the `rules` module's reason to exist — jurisdiction-correct
  generation is the moat, and a free-text editor is the anti-moat.

**`.docx` stays out.** It needs a LibreOffice-headless service beside Gotenberg *and*
contradicts `draft-ingestion`'s shipped invariant that uploaded bytes are never parsed or
rendered. It is already recorded in ROADMAP's queued non-goals; leave it there.

---

## 2. The blocker, and the chosen resolution

`POST /api/agreements/{id}/draft` already accepts and stores an uploaded PDF, and the
downstream flow does not distinguish an uploaded draft from a generated one. **But signing
refuses:**

> *"Where an anchor is absent from the document being signed, the system SHALL refuse the
> request rather than place a signature at a guessed position."* —
> `openspec/specs/esign-signature-placement/spec.md`

The `esign:<role>` anchor is emitted by our template renderer. An uploaded PDF carries
none, so `EsignAnchorLocator.locate` finds nothing. So "the backend already supports BYO"
is false: step one works and step N refuses.

### Decision: append a system-generated execution page

Render a small execution page through the existing compiler's `render: signatures` path —
so the anchor-token derivation stays a single source of truth — and append it to the
uploaded PDF with PDFBox, the same `importPage` pattern `PdfStampComposer` already uses to
prepend the certificate.

**Compose at upload time, not at finalise.** Everything downstream is then byte-identical
to today, and the customer sees the real composed document at review.

**Rejected: drag-to-place** (a PDF.js editor where the customer positions signature boxes).
The objection is structural, not preference. Placements today are *derived*, never stored,
and the locator deliberately runs on the **stamped** PDF — its own javadoc:

> *"Stamp intake prepends the scanned SHCIL certificate as page 1, so every page number
> shifts. Searching the final document handles that with no offset arithmetic at all."*

Coordinates captured on the draft would reintroduce exactly that arithmetic and break
silently if stamp composition ever changes. Add that ZOOP measures `x_coord` from the
**right** edge — where a mirroring error produces confidently wrong output with no error,
verifiable only by inspecting a real signed document — and a per-customer coordinate path
is one we could never actually verify. Also rejected: text-searching the upload for
"Signature" (fragile), and asking customers to type a marker token into their own document
(worse friction than drag).

### Constraints on the appended page

- **Execution furniture only — no covenants, ever.** It is our text on a document we did
  not draft; a clause there would make us a partial author, which is the exposure BYO
  exists to avoid.
- **Match the uploaded document's first-page size**, do not hardcode A4 the way the
  certificate page does. `EsignAnchorLocator.geometry` measures the *smallest* page to
  place per-page strips, so a size mismatch drags every strip inward.
- The anchor renders as it does today: 8px `#ffffff`, white-on-white — invisible to a
  reader, present in the text layer. `display:none` would drop the glyphs and every signing
  request would be refused.

### Known wart, accepted

Most uploaded agreements already carry their own signature block, so we append a second
execution page after it. Mitigated by labelling the page as what it is (a recognised
pattern) and by the per-page strips tying the document together.

---

## 3. Signatures on every page, or the signature page only

`esign-signature-placement` gives each signer a **block** on the execution page plus a
**strip** in the bottom margin of every *other* page, certificate page included. The strip
is explicitly presentation, not law — an Aadhaar eSign covers the whole document either
way — and exists so a bank, a housing society or a registrar's counter flipping page by
page accepts the document.

It does not transfer cleanly to BYO. The strip band is fixed — 95×40pt sitting **26–66pt
from the bottom edge** (`ZoopSignCoordinate.FOOTER_Y_PT`). On our templates that band is
known-empty. On an arbitrary upload it is roughly 0.36–0.92 inch from the paper edge, where
real agreements put page numbers and footers — and a full-page scan occupies it entirely.
The code's own javadoc records that getting that constant wrong "printed a signature over
the body text."

**Strips need no anchor** — they are built from `geometry` alone
(`ZoopEsignProvider`), so this is a free product choice, not a technical constraint.

### Decision: detect, decide once per document, and say so

- **All-or-nothing per document, not per page.** Pages 1, 3 and 4 marked and page 2 bare
  reads at a counter as though pages were swapped — worse than marking one page. It is also
  the smaller change: a pre-pass that decides once, then either runs the existing per-page
  loop or skips it. (Note the loop already excludes the block page, so "every page" has
  never meant literally every page.)
- **Disclose with a remedy; do not ask the customer to confirm.** They have no basis to
  choose between signing every page and signing one, so a blanket confirmation harvests a
  random click. What they *can* act on is uploading the original export instead of a scan —
  and that only works at the upload step, where the file picker is.
- **Asymmetric treatment.** Signature-page-only gets an explicit acknowledgement at review,
  before money moves. Every-page gets a quiet line and nothing to accept.

### The check sees text, not ink

`LineBounds.writeString` walks glyphs only. A letterhead rule, a page border, a watermark
or a background fill in the band is invisible to it. Two consequences, both load-bearing:

- Customer-facing copy must state **what we did** — "we found no text in the footer strip"
  — never "there is room".
- Checking for image XObjects and other non-text content is the cheap tightening, and is
  the difference between the check being honest and being decorative. **Owed as a register
  row** by whichever CR ships the check.

---

## 4. A defect this surfaced, on the shipping path today

`PdfStampComposer` fits the certificate scan into A4 minus a **28pt** margin, so a tall
certificate reaches into the **26–66pt** strip band and a signature is drawn on top of it.
This affects **templated documents today**, not only BYO. It is unobserved because no
signing has completed end to end against a real callback
(`zoop-callback-e2e-on-public-host` is still open) and because a small scan is not upscaled,
so it often leaves room.

A promise that every page carries a mark cannot be kept until the composer reserves that
band, which makes the fix a prerequisite rather than a separate cleanup.

---

## 5. The CR sequence

Four changes, in dependency order. See ROADMAP Track A.

| | Change | Why separate |
|---|---|---|
| 0 | `signing-auth` (already queued) | BYO does not create it, it makes it urgent — a public upload UI widens that hole. `/api/agreements/*/draft` is named in the row. |
| 1 | `estamp-signature-band` | Section 4. Independent of BYO, fixes today's defect, unblocks the every-page promise. |
| 2 | `byo-document-upload` | BYO end to end, **block-only**. |
| 3 | `byo-every-page-signatures` | The footer-band pre-pass, the all-or-nothing decision, the disclosure + acknowledgement UI. Needs 1 and 2. |

**The scope cut that makes CR-2 tractable: ship block-only.** No detection, no disclosure,
no page rail. Honest (we state where signatures appear), reversible, and nothing already
signed changes when CR-3 upgrades it. The cost is one release where BYO output is weaker at
a counter than templated output.

**`signing-auth` first is not free.** That row was widened on 2026-09-11 to cover the three
anonymous reads the status page polls, on top of webhook rate-limiting, verify-failure
logging and a javadoc cleanup — none of which BYO touches. The alternative is BYO carrying
authZ + rate-limit on `/api/agreements/*/draft` and the new fetch-my-draft endpoint only,
with the row staying open and recording the partial. Landing the whole row first is the
recommendation — it is High and "must land before the first real user" — but it is a
schedule cost being chosen, not avoided.

### What must not be split out of CR-2

- **The declaration.** With no template, nothing but the customer determines the instrument
  type, and the stamp-duty basis follows from it. A BYO agreement reaching the stamp queue
  with no declared type is the same hazard `jurisdiction-checkout-gating` was built to
  close. A commercial lease declared as residential rent is under-stamped and inadmissible
  under s.35 until duty and penalty are paid.
- **The ToS delta.** The terms say we generate documents from templates. The promise changes
  in the CR that changes the product.
- Likewise the shipped `LegalDisclaimer` copy cannot be reused — *"your agreement is
  generated from a template"* is false for BYO. A variant is drafted in the mocks.

### What must not be split out of CR-3

Detection and disclosure are one unit. Shipping the check without the message means
silently degrading a customer's document.

---

## 6. Open questions a CR must answer, not assume

1. **What does a BYO agreement pin?** `Agreement.pinEffectiveTemplate` takes plain values
   (a hash and a version map, no layer-set resolution), so mechanically a BYO agreement
   *can* pin the execution page's identity — but that changes what the pin means. Today it
   answers "which authored version produced this deed"; for BYO it would cover only the page
   we appended. [LEGAL-POSTURE.md](LEGAL-POSTURE.md) calls the pin the thing "everything
   below leans on", and under BYO the instrument itself would have no integrity record at
   all. **Likely answer: BYO needs a content hash of the uploaded bytes**, separate from the
   template pin, so "is this the document they approved?" survives the draft freeze — which
   is what makes the freeze mean anything here. If this wants its own review, splitting "the
   BYO agreement shape" (no template, declared dimensions, upload hash) out of CR-2 is a
   defensible fourth seam.
2. **Review has no preview path.** `GET /api/agreements/{id}/preview` renders from the
   **template** (`AgreementController.java`), so BYO needs an owner-scoped fetch-my-draft
   endpoint. It inherits the same `permitAll` problem as the upload endpoint.
3. **Parsing at upload contradicts a shipped invariant.** `draft-ingestion` says uploaded
   bytes are never parsed, rendered or executed; appending requires `Loader.loadPDF` on
   them. The real invariant is *not at upload time* — `PdfStampComposer` already parses the
   draft — so this needs a `MODIFIED` delta. The upside is real: encrypted, corrupt and
   zero-page failures move from the staff stamp step (where they surface days later as
   `STAMP_FAILED`) to the upload, where the customer can act.
4. **Per-page strips on an arbitrary upload** need a `MODIFIED` delta for the body-column
   fallback — `geometry()` already falls back to page proportions when no text column is
   measurable, but the spec says the column is measured from the document.

## 7. Also owed to the register

Before the raising CR archives: the **image-XObject tightening** of the footer-band check
(section 3), and the **remaining mobile frames** (only the upload step is mocked at 390px).
