## Purpose

Defines where a party's signature lands on the instrument that is sent for Aadhaar eSign:
the anchor contract between the rendered document and the provider adapter, how many
placements each signer receives, how a document coordinate becomes a provider coordinate,
and how that placement is proven correct.

## ADDED Requirements

### Requirement: An anchor marks the position a signature occupies

The rendered document SHALL carry one anchor token per signatory, of the form
`esign:<role>`, positioned at the point the signature is intended to occupy -- not
adjacent to it, and not below the block it belongs to. The anchor's located position
SHALL be the position the provider is asked to sign at, so that no offset arithmetic is
needed to relate the two.

Where an anchor is absent from the document being signed, the system SHALL refuse the
request rather than place a signature at a guessed position.

#### Scenario: The anchor sits in the signature area

- **WHEN** a document carrying a signature block is rendered
- **THEN** the anchor for that signatory is located within the blank signature area of the
  block, above the printed party name, and not on any line of printed text

#### Scenario: A missing anchor refuses the request

- **WHEN** a signing request is created for a document in which a signatory's anchor
  cannot be located
- **THEN** the request is refused, no signature position is guessed, and no transaction is
  created with the provider

### Requirement: The anchor is invisible on the page but readable from the document

The anchor SHALL NOT be visible to a reader of the printed or on-screen document: it
carries no meaning for a party and MUST NOT appear on a legal instrument. It SHALL remain
present and extractable in the document's text layer, because that is how its position is
located.

Suppression techniques that remove the token's glyphs from the text layer SHALL NOT be
used.

#### Scenario: The token does not print

- **WHEN** a rendered agreement is read on screen or on paper
- **THEN** no `esign:<role>` token is visible anywhere on the document

#### Scenario: The token is still locatable

- **WHEN** the anchor position is located in that same rendered document after it has been
  stamped
- **THEN** every signatory's anchor is found, with a page number and a position on that
  page

### Requirement: Each signer signs the execution page and every page

Each signer SHALL receive an ordered set of placements, not a single one:

1. a **block placement** in the signature area of the execution page, and
2. a **per-page placement** in the bottom margin of every OTHER page, including the
   stamp-certificate page prepended at intake.

The page carrying the block SHALL NOT also carry a strip: the signature is already there,
and a second one beside it reads as a mistake on a legal instrument.

Each strip SHALL be aligned to the document's **body text column** -- the first signer's
to its left edge, the second signer's to its right edge -- and SHALL sit clear of any
footer the renderer prints. The column SHALL be measured from the document rather than
configured, so a template whose margins change does not silently move every signature.

The per-page placement exists so that a reader who checks page by page -- a bank, a
housing society, a registrar's counter -- accepts the document. It is a presentation
decision, not a legal one: an Aadhaar eSign covers the whole document irrespective of how
many visible marks it carries.

The vendor-neutral create request SHALL carry these placements per invitee, so that
supporting more than one placement is not a property of any single provider adapter.

#### Scenario: Both placements are requested for each signer

- **WHEN** a signing request is created for an agreement with two signatories
- **THEN** each signatory's invitee in the provider request carries both a block placement
  on the execution page and a per-page placement

#### Scenario: The execution page carries the block only

- **WHEN** placements are submitted for a multi-page instrument
- **THEN** the page carrying a signer's signature block carries no strip for that signer

#### Scenario: The stamp page is not excluded

- **WHEN** the instrument's first page is a prepended stamp certificate
- **THEN** the per-page placement applies to that page as well as to the agreement pages

#### Scenario: Strips line up with the body text

- **WHEN** a strip is placed on a page
- **THEN** the first signer's strip begins at the body column's left edge and the second
  signer's ends at its right edge, with neither overlapping the other

#### Scenario: A single-page instrument carries no strip

- **WHEN** the whole instrument is one page, which already carries the block
- **THEN** no strip is placed

### Requirement: Translation into a provider's coordinate system accounts for the signature box

Translating a located anchor into a provider's coordinate system SHALL account for both
the provider's origin convention and the extent of the signature box the provider draws.
A translation that positions only a point, ignoring the box's width and height, SHALL be
treated as incorrect even where the provider accepts the request without error.

The compensation values SHALL be derived from observed provider behaviour and recorded
with the observation that produced them. They SHALL NOT be inferred from vendor
documentation alone where that documentation is unavailable or unverified.

#### Scenario: The drawn signature covers the intended area

- **WHEN** a signature is placed from a located anchor
- **THEN** the drawn signature occupies the intended signature area and does not overlap
  the printed party name, any label, or any other party's block

#### Scenario: A silent-acceptance failure is still a failure

- **WHEN** the provider accepts a placement request and returns success
- **THEN** acceptance alone SHALL NOT be treated as evidence that the placement is
  correct

### Requirement: Placement is proven by inspecting a signed document

Placement correctness SHALL be established by inspecting a document that has actually
been signed end to end, not by asserting over the system's own coordinate arithmetic. A
change to anchor position, to the signature block's layout, or to a provider's coordinate
translation SHALL NOT be considered complete until such an inspection has been performed
and its result recorded.

Any document used for calibration SHALL be synthetic and SHALL carry no party data.

#### Scenario: Arithmetic tests alone do not close the loop

- **WHEN** unit tests over the coordinate translation pass
- **THEN** the placement change is still not complete until a signed document has been
  inspected

#### Scenario: Calibration uses a synthetic document

- **WHEN** provider placement behaviour is calibrated against a live sandbox
- **THEN** the document submitted contains no party name, address, or other personal data
