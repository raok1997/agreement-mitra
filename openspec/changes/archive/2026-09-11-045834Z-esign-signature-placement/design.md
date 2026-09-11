## Context

See `proposal.md` - Why, for motivation and the observed evidence.

The constraints that shape the approach:

- **Two modules own the two halves.** `documents` decides where the anchor is printed;
  `signing` decides what coordinate is sent to a provider. They share no type -- the
  token `esign:<role>` is derived independently on each side (from a signatory field key
  in the compiler, from a signer's role in the signing service) and the PDF text layer is
  the only thing that crosses between them. That is deliberate (design D4 of the ZOOP
  adapter) and this change keeps it.
- **The anchor must survive rendering and stamping.** Placement reads the *stamped*
  document, which has the certificate page prepended, so page numbers shift. Locating in
  the final document is what makes that shift a non-issue -- no offset arithmetic.
- **The vendor's coordinate contract is unverifiable from documentation.** ZOOP's v5
  coordinate page is no longer published and the V4 Confluence page 404s. The only
  authority available is the provider's observed behaviour.
- **Observed behaviour, from the live run** (agreement AMXBWQATYHD, stamped 5-page PDF,
  page 5 at 596 x 842 pt): anchor `esign:owner` at `x_from_left=233.7`,
  `y_from_bottom=596.3`; sent `x_coord=362.2` (mirrored from the right edge),
  `y_coord=596.3`; the drawn box appeared **left of and above** that point. That is
  consistent with the provider treating the given point as the box's right/bottom corner
  and growing the box leftward and upward -- but "consistent with" is not "confirmed",
  which is why calibration is a task and not an assumption.

## Goals / Non-Goals

**Goals:**

- Make the anchor's position and the signature's position the same thing, so correctness
  does not depend on a magic constant relating them.
- Keep the anchor machine-readable while making it invisible to a reader.
- Establish the provider's box geometry from evidence, and record the evidence next to
  the constants it produced.
- Widen the vendor-neutral seam to carry several placements per invitee without teaching
  it any provider's convention.

**Non-Goals:**

- Choosing a signature image size or style -- the provider renders the appearance.
- Making the per-page strip's exact millimetre position a product decision. A bottom
  margin position that clears the page content is sufficient; refining it is cosmetic.
- Any change to the webhook, reconciliation, or status FSM paths.
- Verifying a captured party name against Aadhaar. The proposal removes the claim; adding
  the verification is an `identity` concern.

## Decisions

### D1: Move the anchor into the signature area rather than offsetting in the adapter

The alternative was to leave the template alone and subtract a fixed vertical offset in
the adapter -- "the signature goes 55pt above the anchor".

Rejected because that constant is a property of the template's line heights, and it would
live in the vendor adapter, two modules away from the CSS that determines it. Any change
to the block's typography would silently move every signature. Putting the anchor where
the signature goes makes the relationship structural rather than numeric, and it is the
only version of this that stays correct when the template changes.

### D2: Make the anchor invisible with a colour, not with `display:none`

`display:none` and `visibility:hidden` remove the glyphs from the rendered page, and a
glyph that is not rendered is not in the PDF's text layer -- the locator would find
nothing and every request would be refused. Rendering the token in the page's background
colour (or at zero opacity) keeps the glyphs, and therefore their coordinates, while
showing nothing to a reader.

Trade-off: invisible text in a PDF is a pattern associated with abuse, and a document
scanner may flag it. Accepted -- the alternative is either a visible machine token on a
legal instrument or no anchor-based placement at all. The token carries no personal data
and no claim; it is a positional marker.

An alternative worth naming: place signatures by a named form field or a rectangle
declared in the template's own model, and drop text anchors entirely. That is a larger
change to how `documents` describes a signature zone, and it would still need the same
calibration work. Not now.

### D3: Calibrate the provider's box geometry against a synthetic grid, not against a real agreement

A calibration run submits a one-page document with a printed coordinate grid and anchors
at known positions, then the rendered viewer is inspected to read off which corner of the
box binds to the given point and how large the box is.

Doing this on a real agreement would burn a stamped instrument per attempt and put party
data through a calibration loop. A synthetic grid costs one sandbox transaction, carries
no PII, and yields a number that can be checked by eye.

The resulting constants live in the ZOOP adapter with a comment recording *what was
observed and when* -- so the next person can tell a measured value from a guessed one.

### D4: Model placements as an ordered list on the invitee, first-is-primary

The seam gains `List<Placement>` per invitee instead of one anchor token. Order is
meaningful: the first placement is the primary (execution-page block), later ones are
supplementary.

Rationale: a provider that supports only one position can take the first and behave
sensibly, while a provider that supports many gets them all. The alternative -- a
richer type distinguishing "block" from "strip" -- would push a presentation concept into
a vendor-neutral seam that has so far stayed free of them.

A placement is expressed as an anchor token plus a page scope (`this page` / `all pages`),
not as coordinates: coordinates are the adapter's business.

### D5: Express the per-page strip as a page scope, not as a second anchor per page

The strip cannot be anchored by text -- there is no per-page token, and adding one to
every page would put invisible markers all over the instrument. Instead the strip is a
placement whose page scope is "all pages" and whose position is a constant in the bottom
margin, computed by the adapter from the page box rather than located in the text.

This is the one placement whose position is *not* derived from an anchor, which is worth
stating plainly: it is a margin offset, and if the instrument ever gains a footer, the two
will collide.

## Risks / Trade-offs

- **Calibration is a single observation of an undocumented vendor behaviour** -> Record
  the observation date and the exact values observed alongside the constants; re-run the
  calibration when the provider's API version changes. Treat a placement regression as a
  first-class bug, not a cosmetic one.
- **The provider may change its box size, silently moving every signature** -> The
  end-to-end acceptance step (inspect a signed document) is the only detector. Keep it in
  the release path for any change touching placement, per the spec requirement.
- **Invisible text may be flagged by a document scanner or PDF linter** -> Accepted, see
  D2. The token is positional, carries no data, and appears exactly once per signatory.
- **In-flight orders keep the old geometry** -> An agreement already stamped under the old
  template will still place signatures the old way, because placement reads the stamped
  document. Sandbox data only; no migration. Called out so it is not read as a
  regression.
- **The all-pages strip lands on the stamp certificate page** -> Intended (it matches the
  market convention), but the certificate is a scan whose bottom margin content is not
  under our control. Verify during calibration that the strip does not cover the
  certificate's serial number or vendor block.
- **Widening the seam touches the Leegality adapter too** -> It must keep compiling and
  keep its tests green even though only the ZOOP adapter uses the extra placements.

## Migration Plan

No data migration: nothing about placement is persisted. Deployment is a template change
plus an adapter change, both effective for documents rendered after the change.

Rollback is a revert. An agreement stamped between deploy and rollback keeps the new
anchor layout in its stored PDF, so a reverted adapter would place signatures against an
anchor that has moved -- the reason the acceptance check runs before the change is
considered complete, not after.

## Open Questions

- Whether the per-page strip should also appear on the audit-trail pages the provider
  appends. That is downstream of the provider's own output and can be answered once a
  fully signed document exists to look at.
