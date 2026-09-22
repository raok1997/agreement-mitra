## ADDED Requirements

### Requirement: The signature zone is composed for an eSigned instrument

The compiler SHALL render each signatory's signature zone as a blank signature area, the
party's name, and a role label -- and SHALL NOT render furniture that only a wet-ink
signature can complete.

Specifically:

- The zone SHALL NOT carry `Date` or `Place` fill-in lines. An eSigned document's date
  comes from the eSign appearance, so those blanks can never be completed and leave a
  finished document looking unfinished.
- The role label SHALL NOT assert that the printed name matches any external record. In
  particular it SHALL NOT claim the name is "as per Aadhaar" while nothing verifies the
  captured name against the Aadhaar record.
- The signatory's eSign anchor SHALL be emitted within the blank signature area, so that
  the anchor's position is the position the signature is intended to occupy.
- The anchor SHALL be rendered so that it is invisible to a reader while remaining
  present in the generated document's text layer.

#### Scenario: No wet-ink furniture in the block

- **WHEN** a template with a signatures section is compiled
- **THEN** each signature zone shows the blank signature area, the party name, and the
  role label, and shows no `Date:` or `Place:` fill-in line

#### Scenario: The role label makes no unverified claim

- **WHEN** a signature zone is compiled for a party whose name was typed at capture
- **THEN** the role label names the role only and does not describe the printed name as
  matching the party's Aadhaar record

#### Scenario: The anchor is inside the signature area and does not print

- **WHEN** a signature zone is compiled
- **THEN** the anchor for that signatory appears within the blank signature area, is not
  visible when the document is rendered, and is still present in the generated
  document's text layer

#### Scenario: Preview and PDF stay in agreement

- **WHEN** the same template and data are rendered for the live preview and for the
  generated PDF
- **THEN** both show the same signature zone composition, so what a customer approves is
  what is sent for signature
