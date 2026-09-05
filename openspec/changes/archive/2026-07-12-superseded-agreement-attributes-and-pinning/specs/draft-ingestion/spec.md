## ADDED Requirements

### Requirement: Generating a draft pins the resolved template for byte-stable reproduction

When an agreement's draft is **system-generated** from a template (`POST /api/agreements/{id}/document`),
the system SHALL **pin** the **resolved effective template** on the agreement -- recording its
`templateHash` and `layerVersions` in the same transaction that stores the generated draft -- before
returning. The template SHALL be resolved **once** at generate time via the `documents` module's public
catalog seam; the `signing` module SHALL NOT reach into `documents` internals.

Any **later** render of that agreement's document (audit, re-download, reconciliation) SHALL resolve
the **pinned** `templateHash`, **never** the "current" template for the agreement's dimensions. An
**executed** agreement SHALL therefore reproduce its document **byte-stable**, even after a newer
template version is published for the same `(state, type)`. Preview renders SHALL remain ephemeral and
SHALL NOT be pinned. Generating a document SHALL NOT introduce a new signing state -- the pin is
recorded on the status-less agreement at the existing generate-as-draft step.

A re-generate while the agreement has **no signing request** SHALL overwrite both the draft and the pin
(mirroring the existing draft-overwrite rule); once a signing request exists, generation is already
rejected `409 Conflict`, so the pin is frozen alongside the draft.

#### Scenario: Generate pins the resolved template

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an agreement with no signing request
- **THEN** the system resolves the effective template, stores the generated PDF as the draft, and
  records that template's `templateHash` and `layerVersions` on the agreement

#### Scenario: A later render reproduces byte-stable from the pin

- **WHEN** an agreement's document is generated, a **newer** template version is later published for
  the same `(state, type)`, and the agreement is re-rendered
- **THEN** the system resolves the **pinned** `templateHash` (not the newer version) and reproduces the
  identical document

#### Scenario: Re-generate before signing overwrites the pin

- **WHEN** a client POSTs `/api/agreements/{id}/document` twice for an agreement with no signing request
- **THEN** both succeed and the later render overwrites both the stored draft and the recorded pin

#### Scenario: Pin is frozen once signing has started

- **WHEN** a client POSTs `/api/agreements/{id}/document` for an agreement that already has a signing
  request
- **THEN** the system responds `409 Conflict`, leaving the stored draft and the recorded pin unchanged
