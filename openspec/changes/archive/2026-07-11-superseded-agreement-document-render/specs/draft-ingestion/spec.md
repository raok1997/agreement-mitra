## ADDED Requirements

### Requirement: An agreement draft may be system-generated from a template

An agreement's draft PDF SHALL be obtainable **either** by upload (the existing path) **or**
by **system generation** from the rental-agreement template (`POST /api/agreements/{id}/document`).
However the draft is produced, it SHALL be stored under the same object-storage draft key and
SHALL feed the existing stamp and eSign flow unchanged; downstream steps SHALL NOT distinguish
an uploaded draft from a generated one.

Generating a document SHALL be permitted while the agreement has **no signing request yet**,
and SHALL **overwrite** any prior draft (uploaded or generated). Once a signing request
exists for the agreement, the draft SHALL be treated as locked (generation is rejected), so a
document cannot change under an in-flight signature.

#### Scenario: A generated draft feeds signing like an uploaded one

- **WHEN** a client generates the agreement's document and then requests a signing
- **THEN** the signing flow finds the generated PDF as the draft and proceeds exactly as it
  would for an uploaded draft

#### Scenario: Regeneration is blocked once signing has started

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an agreement that already has a
  signing request
- **THEN** the system rejects the regeneration and leaves the existing draft unchanged
