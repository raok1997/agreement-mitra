## Why

A live ZOOP sandbox run put both signatures in the wrong place on a real stamped
agreement: the box landed left of, and above, its anchor -- on top of the
`Date: ____ Place: ____` line rather than in the blank signature area. The same run
exposed two further defects on the instrument itself: the machine token `esign:owner` /
`esign:tenant` **prints** on the signature page (9px grey, not hidden), and the block
carries wet-ink furniture -- an unverifiable "(as per Aadhaar)" claim and Date/Place
fill-in lines that stay blank forever on an eSigned document.

This is the legal artifact the customer files with a bank, a society, or a court. A
signature in the wrong place, a stray machine token, and a permanently blank date line
are all defects a reader can see, and none of them are caught by any existing test --
design D4 already named visual inspection of a real signed document as the acceptance
check, and that check is what caught this.

## What Changes

- **Anchor moves to where the signature belongs.** The compiler emits the anchor as the
  last element of the signature zone, roughly 55pt below the blank signature area, so
  "place the signature at the anchor" can never land correctly. The anchor moves into the
  signature area itself, making the anchor position the intended signature position.
- **The anchor stops printing.** It becomes visually invisible while remaining in the
  PDF text layer, because `EsignAnchorLocator` reads that layer. **Constraint:**
  white/transparent text only -- `display:none` and `visibility:hidden` remove the glyphs
  from the text layer and would break placement entirely.
- **Signature on every page.** Each signer gets a second, constant bottom-margin
  placement applied to all pages (including the prepended stamp-certificate page), in
  addition to the execution-page block. Not legally required -- an Aadhaar eSign under
  IT Act s.3A signs the whole PDF -- but adopted so third-party readers who check page by
  page accept the document, matching the market convention visible in Leegality-signed
  agreements.
- **The vendor-neutral create request carries more than one placement per signer.**
  Today an invitee carries a single anchor token; it becomes an ordered set of
  placements, so a provider adapter can emit both the block and the per-page strip.
- **Placement compensates for the signature box's own extent.** The adapter mirrors the
  x axis correctly but treats the anchor point as if the box had no width or height.
  The observed behaviour is that the provider grows the box left and up from the given
  point. The compensation constants SHALL be derived from a calibration run against the
  sandbox and recorded, not assumed -- ZOOP's v5 coordinate documentation is no longer
  published.
- **Signature-block copy is corrected for an eSigned document.** The party name stays.
  The "(as per Aadhaar)" label is dropped, because nothing verifies the captured name
  against the Aadhaar record -- the live run showed the document reading
  "Padavala Venkata Nageswararao" against an eSign certificate name of
  "Padavala Venkat...". The `Date: ____ Place: ____` line is dropped, because the eSign
  appearance carries its own timestamp and those blanks can never be filled.

Not in scope: verifying a captured name against Aadhaar (an `identity` concern), and any
change to the async webhook/reconciliation flow.

## Capabilities

### New Capabilities

- `esign-signature-placement`: where a signature is placed on the instrument -- the
  anchor contract between the rendered document and the provider adapter, the set of
  placements each signer receives (execution-page block plus all-pages strip), the
  translation from document coordinates into a provider's convention including
  compensation for the signature box's extent, and the requirement that placement is
  verified by inspecting a real signed document rather than by asserting over our own
  arithmetic.

### Modified Capabilities

- `template-document-projection`: the signature zone's composition changes -- the anchor
  moves into the signature area and becomes invisible-but-extractable, and the block
  drops the "(as per Aadhaar)" label and the Date/Place fill-in lines.
- `signing-request`: the `EsignProvider` seam's vendor-neutral create request changes
  from one anchor per invitee to an ordered set of placements per invitee.

## Impact

**Code**
- `documents`: `TemplateCompiler` -- signature-zone HTML (anchor position within the
  zone, block copy) and the `.sign-anchor` style rule.
- `signing`: `SigningRequestService` (builds invitees and their anchors),
  `SignRequest.Invitee` (one anchor becomes many placements), and the ZOOP adapter --
  `EsignAnchorLocator`, `AnchorPosition`, `ZoopSignCoordinate`, `ZoopEsignProvider`.
- The Leegality adapter shares the seam and must keep compiling against the widened
  request shape.

**Documents already rendered.** Agreements drafted before this change carry the old
anchor layout. Placement reads the stamped PDF at request time, so an in-flight order
stamped under the old template keeps the old (wrong) geometry. A migration is neither
possible nor needed for sandbox data; the behaviour is called out so it is not
mistaken for a regression.

**No schema change.** No new table, column, or Flyway migration -- placement is derived
from the document at request time and is not persisted.

**FSM.** No transition is added, removed, or re-ordered. The change affects only what is
sent to the provider during `STAMPED -> SIGN_REQUESTED`, and what the rendered document
looks like before that.

**Async signing/webhook flow.** Unchanged -- no sequence diagram is warranted. Webhook
verification, the authoritative-status read, and reconciliation are untouched.

**PII / security review**
- **New or moved PII flow: none.** No Aadhaar number, VID, OTP, or KYC attribute is
  read, stored, logged, or transmitted by this change. The party name already reaches
  the provider as an invitee field and continues to do so unchanged.
- **What does change** is the *position* of a signature and the *visibility* of a
  non-PII machine token (`esign:<role>`), which carries no personal data. Removing the
  "(as per Aadhaar)" label removes an unverified assertion about a party, which reduces
  rather than increases exposure.
- **Logging** stays as it is: no document content, no coordinates tied to a named party,
  and no rendered text may be logged. Calibration output SHALL use a synthetic document
  with no real or dummy party data.
- **Secrets:** none introduced. Provider credentials stay env-only.
- **Sandbox + dummy data only** is preserved: the calibration run uses the ZOOP test
  host and a synthetic grid document, never a customer instrument.
