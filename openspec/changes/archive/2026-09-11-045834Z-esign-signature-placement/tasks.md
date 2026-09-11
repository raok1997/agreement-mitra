## 1. Calibrate the provider's box geometry

- [x] 1.1 Build a throwaway calibration document: one A4 page, a printed coordinate grid
      at a known pitch, and two anchor tokens at known, recorded positions. No party data
      of any kind.
- [x] 1.2 Submit it to the ZOOP sandbox with coordinates computed the way the adapter
      computes them today, and open the returned viewer URL.
- [x] 1.3 Record, in the adapter as a comment next to the constants: which corner of the
      drawn box binds to the submitted point, the box's width and height in points, the
      date observed, and the API version observed against.
- [x] 1.4 Derive the compensation needed to make a given anchor point land at the
      *centre-left* of the intended signature area, and express it as named constants
      rather than inline arithmetic.

## 2. documents -- signature zone composition

- [x] 2.1 Move the anchor element into the blank signature area of the zone, above the
      printed party name, so the anchor's position is the signature's position.
- [x] 2.2 Render the anchor invisible while keeping its glyphs in the text layer
      (background-colour or zero-opacity text). Do not use `display:none` or
      `visibility:hidden`.
- [x] 2.3 Remove the `Date: ____ Place: ____` line from the signature zone.
- [x] 2.4 Change the role label so it no longer claims the printed name is "as per
      Aadhaar".
- [x] 2.5 Confirm the live preview and the generated PDF still come from the one compiler
      and show the same zone (parity requirement of `template-document-projection`).

## 3. signing -- widen the vendor-neutral seam

- [x] 3.1 Replace the invitee's single anchor token with an ordered list of placements,
      each carrying an anchor token and a page scope (this page / all pages).
- [x] 3.2 Build both placements per signer where the request is assembled: the
      execution-page block from the signatory's anchor, and the all-pages strip.
- [x] 3.3 Keep the Leegality adapter compiling and green against the widened shape --
      first placement wins, remaining placements are not silently dropped without a
      deliberate, commented decision.
- [x] 3.4 Keep `ModularityTests` green: no `documents` type may cross into `signing`, and
      nothing outside `signing` may reference an adapter package.

## 4. zoop adapter -- emit both placements

- [x] 4.1 Apply the calibrated compensation in the coordinate translation so the drawn box
      covers the intended area rather than a point.
- [x] 4.2 Emit the execution-page block placement from the located anchor, on the page the
      anchor was found (no page arithmetic -- the stamped document is what is searched).
- [x] 4.3 Emit the all-pages strip placement using the vendor's all-pages page number, at a
      constant bottom-margin position computed from the page box, one position per signer
      so the two parties' strips do not overlap.
- [x] 4.4 Keep the existing fail-closed behaviour: a missing anchor refuses the request
      rather than guessing a position.

## 5. Unit tests

- [x] 5.1 Coordinate translation: origin mirroring plus box compensation, including a
      case that would pass under point-only translation and fail under the corrected one
      (the regression this change exists for).
- [x] 5.2 All-pages strip position: computed from the page box, distinct per signer, and
      inside the page for both A4 and a non-A4 page size.
- [x] 5.3 Placement list: an invitee with two placements keeps both, in order; an adapter
      that consumes only the first does not drop the rest silently.
- [x] 5.4 Compiler: the signature zone contains no `Date`/`Place` line, makes no
      "as per Aadhaar" claim, and carries the anchor inside the signature area.
- [x] 5.5 Fail-closed: a document with no anchor for a signatory produces a refusal, not a
      default position.

## 6. Integration tests

- [x] 6.1 Render a template through the real compiler and renderer, then assert every
      signatory's anchor is still locatable in the produced PDF's text layer after the
      visibility change -- the test that would have caught `display:none`.
- [x] 6.2 Composite a stamp page onto that PDF as intake does, then assert the anchors are
      located on the shifted page number, with a position inside the page bounds.
- [x] 6.3 Drive the create-request path against the provider stub and assert the submitted
      body carries two placements per signer, one on the anchor's page and one all-pages.
- [x] 6.4 Assert no anchor token, coordinate, or document content reaches the logs on any
      of these paths.

## 7. Acceptance -- inspect a real signed document

- [x] 7.1 Take one order through the full pipeline on the sandbox: draft, stamp, signing
      request, both parties sign. **Done 2026-09-11** (see the inspection record below).
- [x] 7.2 Inspect the returned signed PDF: signatures sit in the signature areas, overlap
      no printed text and no other party's block, the per-page strip appears on every page
      including the stamp certificate page, and no `esign:<role>` token is visible
      anywhere. **PASS on all four.**
- [x] 7.3 Confirm the strip on the stamp-certificate page does not cover the certificate's
      serial number or the vendor block. **PASS, by a wide margin** -- the certificate's
      printed content occupies the upper third of the sheet and the strips sit at the foot.
- [x] 7.4 Record the inspection result (and the document it was performed on) in the
      change before archiving -- the spec requires the loop be closed by inspection, not
      by arithmetic.

### Inspection record -- 2026-09-11

**Document inspected.** Tracking reference `AM63AV7B8WZ`, Telangana residential, four physical
pages: a composited SHCIL-style e-stamp certificate (dummy: Rs. 100 Telangana non-judicial,
serial `AA 000000`, `SAMPLE STAMP VENDOR`) followed by three agreement pages footered
"Agreement page 1..3 of 3". Signed on the ZOOP **test host**, viewer **v4.2.0**, transaction
`6aa386abc8889fad5a2e857a`. Both parties signed via Aadhaar eSign at 10:13:20 and 10:15:03 IST.
Dummy party data throughout (`j k` / `h k`); signer addresses redacted from this record.

**Result: all seven placement checks PASS.**

1. **Signatures land inside their own signature areas** -- and, decisively, **are not mirrored**.
   Both signature blocks sit in the `IN WITNESS WHEREOF` zones on the final agreement page,
   directly above the rule and the printed party name; owner on the left, tenant on the right.
   This is the acceptance criterion for S4: the mirrored x axis (`xCoord` is distance from the
   RIGHT edge) fails silently, producing valid coordinates and a successfully signed document
   with the signature on the wrong side. It did not.
2. **No signature overlaps printed text.** The compensating shift by `BOX_WIDTH_PT` put the
   box's left edge on the anchor rather than hanging it leftwards over template content.
3. **The two signers' blocks do not overlap each other** on any page; lane 0 and lane 1 stay
   clearly separated at the content column's left and right.
4. **The per-page strip appears on all four pages**, the composited certificate page included,
   seated between the body text and the tracking footer -- the `FOOTER_Y_PT = 26` band holds
   against a real render (this is the value that was once 55pt and printed a signature across
   every page's last lines).
5. **The strip covers neither the certificate serial nor the vendor block.** Worth noting for
   whoever re-runs this: the clearance was large because this certificate's printed content sits
   in the upper third. A denser scan is a different test, and the geometry gives no guarantee
   about a page our renderer did not lay out.
6. **No `esign:<role>` token is visible.** Both tokens are present in the **text layer** --
   confirmed by extraction, and required, since that layer is how the provider locates them --
   and neither is legible in the rendered page. Painted-in-page-colour is working as specified;
   `display:none` would have broken location instead.
7. **Page numbering accounts for the composited certificate.** The anchored placements landed on
   physical page 4 (= agreement page 3 of 3), where the signature block lives. Had the +1 shift
   been missed they would have landed on physical page 3.

**Out of scope of this change, observed on the same document and already registered:** the
Telangana deed carries two choice-of-law clauses -- witnesseth clause 9 ("the laws of India")
and Statutory clause 1 ("the laws of India **and** the tenancy laws applicable in ... Telangana").
That is `tg-governing-law-duplication` in the follow-up register, now confirmed against a real
rendered instrument rather than inferred from the YAML.

**What this record does NOT cover.** The ZOOP callback never reached the app on this run (local
tunnel), so the transaction is still `SIGN_REQUESTED` on our side with no stored artifacts. That
is `zoop-aadhaar-esign` task 8.5 and it remains open there. It does not affect any check above:
every one of them was made against the signed PDF the provider produced, and placement is
decided before a webhook exists.
