## Purpose

Lets a customer bring their own agreement document instead of choosing one of our
templates, and carries it through the existing stamp and Aadhaar eSign flow by
appending a system-generated execution page that the signature anchors live on.

## ADDED Requirements

### Requirement: A customer MAY bring their own document instead of choosing a template

The system SHALL offer, alongside the published template catalog entries, an entry
point that starts an agreement from a customer-supplied document -- a PDF or a
Word (`.docx`) file -- rather than from a template. Choosing it SHALL lead to an
upload step, and the resulting agreement SHALL carry no selected template, no
template content hash and no template layer versions.

The entry point SHALL NOT be a published catalog entry. A catalog entry is
system-owned metadata for a renderable template with a content hash and resolved
layer versions, none of which a bring-your-own agreement has, so presenting one
would make the catalog describe a template that does not exist.

#### Scenario: The upload entry point is offered with the templates

- **WHEN** a customer opens the agreement entry step
- **THEN** an option to upload their own document is presented alongside the
  published template entries
- **AND** choosing it leads to an upload step rather than to a capture form for a
  template

#### Scenario: The entry point is not a catalog entry

- **WHEN** a client lists the published template catalog
- **THEN** the bring-your-own entry point is not among the returned entries
- **AND** no catalog entry exists whose template body cannot be resolved

#### Scenario: A bring-your-own agreement pins no template

- **WHEN** an agreement is created through the bring-your-own flow
- **THEN** it records no selected template, no template content hash and no
  template layer versions

### Requirement: A bring-your-own agreement SHALL declare the facts its duty and execution page depend on

The system SHALL require, before a bring-your-own agreement is admitted to paid
fulfilment, a declaration carrying the **duty jurisdiction** (the state in which
the property lies), the **instrument kind**, the **usage**, the **execution
date**, the **term**, the **rent schedule**, the **deposits**, and the **party
list** with each party's role and full name as per Aadhaar.

With no template there is nothing but the declaration to establish the
instrument, and the stamp duty basis follows from it. An instrument assessed under
the wrong article is under-stamped and inadmissible until duty and penalty are
paid, so a bring-your-own agreement reaching the stamp queue with no declared
instrument SHALL be refused rather than assessed on a default.

The declared facts SHALL be the same normalized fact set the stamp duty calculator
already accepts for a templated agreement, so that one calculator serves both.
The declaration SHALL be recorded on the agreement and SHALL be subject to the
same freeze rules as a templated agreement's terms.

#### Scenario: A declared agreement is quoted by the existing calculator

- **WHEN** a bring-your-own agreement is declared with a duty jurisdiction that has
  a chargeable rule, and a term, rent schedule and deposits the rule can quote
- **THEN** the stamp duty calculator quotes it from the declared facts
- **AND** the quote is produced by the same calculation path a templated agreement
  uses

#### Scenario: An undeclared agreement cannot be paid for

- **WHEN** a bring-your-own agreement without a complete declaration is submitted
  for order placement
- **THEN** the system refuses it as `application/problem+json` naming what is
  missing
- **AND** no order is placed and no default instrument kind is assumed

#### Scenario: The declaration cannot be the national dimension

- **WHEN** a declaration names the national dimension as the duty jurisdiction
- **THEN** the system refuses it
- **AND** the agreement remains draft-only

#### Scenario: Parties are still required for signing

- **WHEN** a bring-your-own agreement is declared
- **THEN** each party's role and full name as per Aadhaar is recorded, because the
  execution page and the eSign invitees are built from them

### Requirement: An uploaded document SHALL be parsed and validated before it is accepted

The system SHALL parse an uploaded document at the upload step -- after converting
a Word upload to PDF -- and SHALL reject, with `400 Bad Request` as RFC 9457
`application/problem+json` and nothing stored, a document that cannot be parsed,
is encrypted or password-protected, has zero pages, or whose page count or page
dimensions fall outside the bounds the system can compose and stamp.

The rejection SHALL name the condition in terms the customer can act on and SHALL
NOT echo parser diagnostics, the client-supplied filename, or any part of the
document's content.

Parsing at upload moves these failures from the staff stamp step, where they
surface days later as a terminal stamp failure, to the moment the customer still
has the file in front of them.

#### Scenario: An encrypted PDF is refused at upload

- **WHEN** a customer uploads a password-protected PDF
- **THEN** the system responds `400 Bad Request` as `application/problem+json`,
  stores nothing, and tells the customer the document is protected
- **AND** no agreement reaches the stamp queue

#### Scenario: A corrupt PDF is refused at upload

- **WHEN** a customer uploads bytes that begin with the PDF signature but cannot
  be parsed as a document
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and
  stores nothing
- **AND** the response carries no parser diagnostic and no filename

#### Scenario: A zero-page or out-of-bounds document is refused

- **WHEN** a customer uploads a parseable PDF with no pages, or with a page count
  or page size outside the composable bounds
- **THEN** the system responds `400 Bad Request` as `application/problem+json` and
  stores nothing

### Requirement: A Word upload SHALL be converted to PDF and the conversion SHALL be what is reviewed

The system SHALL convert an uploaded Word document to PDF on receipt, and the
converted PDF SHALL be the document that is composed, reviewed, stamped and
signed.

The signed instrument is therefore a **rendering** of the customer's file rather
than the file itself: a converted document may paginate or lay out differently
from what the customer saw in their word processor, because font substitution and
layout engines differ. The system SHALL therefore present the **converted**
document at the review step, before any payment, so that what the customer
approves is what will be signed. The system SHALL NOT present the customer's own
rendering, or a description of it, in place of the conversion.

The system SHALL retain the originally uploaded bytes alongside the converted PDF,
and the content hash SHALL be taken over the **uploaded** bytes, so that what the
customer supplied remains identifiable after conversion.

A document that cannot be converted SHALL be rejected at the upload step with
`400 Bad Request` as `application/problem+json`, storing nothing and echoing no
converter diagnostic.

#### Scenario: The converted document is what the customer reviews

- **WHEN** a customer uploads a Word document
- **THEN** the review step shows the converted PDF with the execution page appended
- **AND** that same document is what is stamped and signed

#### Scenario: The original upload is retained and hashed

- **WHEN** a Word document is accepted
- **THEN** the system retains the uploaded bytes as received
- **AND** the recorded content hash is over those bytes, not over the conversion

#### Scenario: An unconvertible document is refused at upload

- **WHEN** a customer uploads a Word document the converter cannot render
- **THEN** the system responds `400 Bad Request` as `application/problem+json`,
  stores nothing, and echoes no converter diagnostic

### Requirement: A system-generated execution page SHALL be appended at upload time

The system SHALL append a system-generated execution page to the uploaded document
and SHALL store the **composed** document as the agreement's signable draft, under
the same draft key and through the same storage seam a generated draft uses.

Composition SHALL happen at upload, not at order placement or signing, so that
every downstream step operates on a document identical in kind to a generated one
and the customer reviews the document that will actually be signed.

The execution page SHALL carry one signature anchor per signatory, derived by the
same mechanism that derives anchors for a generated document, so that anchor
derivation has a single source of truth. The anchor SHALL be invisible to a reader
and present in the text layer, as it is for a generated document.

#### Scenario: The stored draft is the composed document

- **WHEN** a customer uploads a valid PDF for a declared bring-your-own agreement
- **THEN** the system stores a document consisting of the customer's pages followed
  by the execution page
- **AND** the stored draft is retrievable and reviewable before payment

#### Scenario: Signing finds the anchors on the appended page

- **WHEN** a signing request is created for a bring-your-own agreement
- **THEN** every signatory's anchor is located on the appended execution page
- **AND** the request is not refused for a missing anchor

#### Scenario: Downstream steps do not distinguish the composed draft

- **WHEN** a composed bring-your-own draft reaches stamping and signing
- **THEN** the stamp certificate is prepended and the signing request is created
  exactly as they are for a generated draft

### Requirement: The execution page SHALL carry execution furniture only

The appended page SHALL carry only what is needed to execute the document:
signature areas, party names and roles, a date line, and a label identifying the
page as a system-appended execution page. It SHALL NOT carry any covenant, term,
recital, or other operative clause.

The document is one we did not draft. A clause on the page we appended would make
us a partial author of the instrument, which is the exposure the bring-your-own
flow exists to avoid.

The page SHALL be labelled as what it is, because most uploaded agreements already
carry their own signature block and the appended page follows it.

#### Scenario: No operative clause appears on the appended page

- **WHEN** an execution page is composed for any bring-your-own agreement
- **THEN** it carries signature areas, party names and roles, a date line and a
  page label
- **AND** it carries no covenant, term, recital or other operative clause

#### Scenario: The page identifies itself

- **WHEN** a customer or a counter reads the composed document
- **THEN** the appended page is labelled as a system-appended execution page,
  distinguishing it from any signature block the customer's own document carries

### Requirement: The execution page SHALL match the uploaded document's page size

The appended page's dimensions SHALL be taken from the uploaded document's first
page rather than from a fixed paper size.

Signature placement geometry is measured from the document, and the measurement
takes the smallest page, so an appended page smaller than the customer's pages
would drag every placement inward and an appended page larger would leave the
signature area short of where it is drawn.

#### Scenario: A non-A4 upload gets a matching execution page

- **WHEN** a customer uploads a document whose pages are not A4
- **THEN** the appended execution page carries those same dimensions
- **AND** the located anchor positions fall inside the intended signature areas

#### Scenario: Placement geometry is unaffected by the appended page

- **WHEN** placements are computed for a composed document
- **THEN** the appended page does not change the geometry measured from the
  customer's pages

### Requirement: A bring-your-own instrument SHALL receive the block placement only

Each signatory of a bring-your-own instrument SHALL receive the block placement in
the signature area of the appended execution page, and SHALL receive no per-page
placement on any other page.

The per-page strip band is a fixed region near the bottom edge of the page. On a
system-generated document that band is known to be empty; on an arbitrary upload
it is where real agreements print page numbers and footers, and a full-page scan
occupies it entirely. Placing a strip there without first establishing that the
band is free would draw a signature over the customer's text.

The system SHALL state to the customer, before payment, that signatures appear on
the appended execution page and not on every page.

#### Scenario: Only the block is requested for a bring-your-own instrument

- **WHEN** a signing request is created for a bring-your-own agreement with two
  signatories
- **THEN** each signatory's invitee carries exactly one placement, the block on the
  appended execution page
- **AND** no per-page placement is submitted for any page, including the prepended
  stamp certificate page

#### Scenario: Where signatures appear is stated before money moves

- **WHEN** a customer reviews a composed bring-your-own document before payment
- **THEN** the system states that signatures will appear on the appended execution
  page and not on every page

### Requirement: A bring-your-own agreement's integrity record SHALL be the hash of the uploaded bytes

The system SHALL record a content hash of the uploaded document's bytes on the
agreement, server-managed and never client-settable, set when the upload is
accepted.

A templated agreement answers "is this the document they approved?" from its
pinned template content hash and layer versions. A bring-your-own agreement has
no template, so without a hash of the uploaded bytes the instrument itself would
carry no integrity record at all and the draft freeze would guarantee nothing.

Re-uploading while the draft is still replaceable SHALL replace the hash with the
new document's. Once the draft is finalized the hash SHALL NOT change.

#### Scenario: The hash is recorded on acceptance

- **WHEN** a valid document is uploaded for a bring-your-own agreement
- **THEN** the agreement records a content hash of the uploaded bytes

#### Scenario: The hash is not client-settable

- **WHEN** a client supplies a content hash in any request body
- **THEN** the system ignores it and keeps its own computed value

#### Scenario: The hash follows a replacement upload and then freezes

- **WHEN** a customer replaces the uploaded document while no signing request
  exists, and later a signing request is created
- **THEN** the recorded hash is the replacement document's
- **AND** it does not change after the draft is finalized

### Requirement: The composed draft SHALL be retrievable for review under owner scope

The system SHALL serve the stored composed draft back to the agreement's owner or
link holder for review, and SHALL NOT attempt to render a bring-your-own agreement
from a template.

The existing preview endpoint renders the document from the selected template's
definition. A bring-your-own agreement has no template, so review has no path
without a fetch of the stored document.

The response SHALL be non-cacheable, SHALL carry the server-set PDF content type,
and SHALL NOT include the client-supplied filename.

#### Scenario: The owner reviews the composed document

- **WHEN** an authorized caller requests the draft of a bring-your-own agreement
- **THEN** the system returns the stored composed PDF, non-cacheable, with a
  server-set content type

#### Scenario: Template rendering is not attempted

- **WHEN** a template render or preview is requested for a bring-your-own agreement
- **THEN** the system refuses it as `application/problem+json` rather than
  rendering an unrelated template
