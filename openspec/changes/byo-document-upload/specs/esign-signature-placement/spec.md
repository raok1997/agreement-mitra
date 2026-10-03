## MODIFIED Requirements

### Requirement: Each signer signs the execution page and every page

Each signer of a **system-generated** instrument SHALL receive an ordered set of
placements, not a single one:

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

Each signer of a **customer-supplied (bring-your-own) instrument** SHALL receive the block
placement only, on the system-appended execution page, and SHALL receive no per-page
placement. The strip band is a fixed region near the bottom edge; on a generated document
it is known to be empty, but on an arbitrary upload it is where page numbers and footers
are printed and a full-page scan occupies it entirely, so a strip placed there without
first establishing that the band is free would draw a signature over the customer's text.
Establishing that is a separate capability; until it exists, where signatures appear SHALL
be stated to the customer before payment rather than the band being used on trust.

The vendor-neutral create request SHALL carry these placements per invitee, so that
supporting more than one placement is not a property of any single provider adapter.

#### Scenario: Both placements are requested for each signer

- **WHEN** a signing request is created for a system-generated agreement with two
  signatories
- **THEN** each signatory's invitee in the provider request carries both a block placement
  on the execution page and a per-page placement

#### Scenario: The execution page carries the block only

- **WHEN** placements are submitted for a multi-page instrument
- **THEN** the page carrying a signer's signature block carries no strip for that signer

#### Scenario: The stamp page is not excluded

- **WHEN** a system-generated instrument's first page is a prepended stamp certificate
- **THEN** the per-page placement applies to that page as well as to the agreement pages

#### Scenario: Strips line up with the body text

- **WHEN** a strip is placed on a page
- **THEN** the first signer's strip begins at the body column's left edge and the second
  signer's ends at its right edge, with neither overlapping the other

#### Scenario: A single-page instrument carries no strip

- **WHEN** the whole instrument is one page, which already carries the block
- **THEN** no strip is placed

#### Scenario: A customer-supplied instrument carries the block only

- **WHEN** a signing request is created for a bring-your-own agreement
- **THEN** each signatory's invitee carries exactly one placement, the block on the
  appended execution page
- **AND** no strip is placed on any page, including the prepended stamp-certificate page

#### Scenario: The narrower marking is disclosed before payment

- **WHEN** a customer reviews a composed bring-your-own document before paying
- **THEN** the system states that signatures will appear on the appended execution page
  and not on every page
