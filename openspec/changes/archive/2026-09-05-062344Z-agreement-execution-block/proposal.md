## Why

The system generates a complete, richly data-driven rental-agreement draft (parties, schedule, term,
financial, charges & utilities, occupancy, dispute, statutory) -- but the rendered document **ends at
the dispute clause with no execution / signature section**. The template declares an "In Witness
Whereof" section that renders **nothing**. That is the hard blocker for the next milestone -- Aadhaar
OTP eSign -- because the signing provider (Leegality / Digio) has **no signature zone or anchor** to
place the digital signature.

This change renders a proper **execution block with per-signer signature zones + stable eSign
anchors**, plus the small set of still-missing boilerplate clauses and document furniture, so the
generated draft becomes a genuinely **signable, production-shaped legal document**.

**Scope is deliberately narrow.** The rich clause set (maintenance, utilities, occupancy, pets,
parking, stamp / registration, fixtures, special conditions, lock-in, notice, escalation) **already
ships as optional sections** in the TG template -- verified live against
`GET /api/templates/form?state=TG&type=residential` (37 fields, 11 sections). This change does **not**
re-do them.

## Template-first (the organizing principle)

The system is a **templating platform** -- prefer **template-definition edits over code**. Split the
work by which side of the engine it lands on:

| Change | How | Where |
| --- | --- | --- |
| Boilerplate clauses (notices, governing law, severability, entire-agreement) | **Template edit** -- system-owned static clauses | `documents/.../sets/rental/base.yaml` |
| Execution-block wording ("IN WITNESS WHEREOF ...") | **Template edit** -- section already declared; compiler emits the wording | `sets/rental/base.yaml` |
| Signatory / signature-zone render + eSign **anchors** | **Engine** -- EXTEND the existing `RenderKind.SIGNATURES` render into per-signer zones + detectable non-PII markers | `documents` compiler |
| Witnesses (optional add-on section, default off) | **Template edit** -- optional witness fields + optional section, opt-in via activeSections | `sets/rental/base.yaml` + compiler |
| Anchor -> provider signature-field mapping | **Engine** -- `signing` derives `esign:<role>` from the signer roles it holds | `signing` (EsignProvider) |
| Document furniture (agreement ref, page X of Y) | **Engine** -- Gotenberg header/footer render layer | `documents` render |

Only the anchor emission (extending the existing `SIGNATURES` render -- the block type already
exists) and the anchor->field mapping genuinely need code; the clauses are template edits. This keeps
the boundary intact and lets the legal-authoring path (future admin builder) own the clause text
later.

## What Changes

- **Execution / signature block** -- fill the empty "In Witness Whereof" section: an "IN WITNESS
  WHEREOF ..." closing plus a **signature zone per signer** (Owner, Tenant) -- signature area, name
  (as per Aadhaar), date, place. Renders for the actual signer set (single-party-per-role today;
  multi-party is a follow-on).
- **eSign anchors** -- each signature zone emits a stable, **non-PII** anchor (e.g. `esign:owner`,
  `esign:tenant`) that the `signing` module maps to the provider's signature field at
  create-signing-request. `documents` renders the anchor; `signing` consumes it. `documents` stays
  **eSign-agnostic**.
- **Witnesses (optional section, default off)** -- delivered as an **optional add-on section** (like
  the other optional clauses): witness name / address fields that default empty; the section renders
  only when the user **adds it** (opt-in via activeSections, the engine's actual optional-section
  gating -- refined from "only when filled"). Witnesses are **not required** for an 11-month
  unregistered eSigned lease (the eSign audit trail is the attestation), so the residential default
  renders none. Witness details are printed attestation **data** (escaped), not eSign signers --
  witnesses-must-sign is a follow-on.
- **Boilerplate clauses** (template edit) -- Notices (service of notice), Governing law, Severability,
  Entire-agreement / Amendment -- added to `sets/rental/base.yaml` as system-owned static clauses
  (the TG layer already carries a Telangana-specific `tgGoverningLaw`; the national clause stays
  general).
- **Document furniture** -- a unique **agreement reference** on the document + **page numbers /
  header-footer**.
- **Audit-trail annexure** -- anticipate the provider-appended eSign audit page (masked Aadhaar,
  txn id, OTP verification): no render by us; layout leaves room; documented.

**Out of scope (deferred, tracked):**
- Brokerage / renewal fields -- optional add-on sections, future.
- Date / amount / clause-numbering **formatting** (dates as "1 July 2026", amounts in words with
  Indian grouping, clause-numbering restart) -- folds into `rental-document-content-v2` (already in
  legal review).
- The actual eSign **OTP integration** -- the subsequent signing module; this CR only produces the
  **anchored signable artifact**.
- TG **e-stamp** integration -- a separate stamp-provider change.

## Capabilities

### Modified Capabilities
- **document-rendering** -- SHALL render an execution / signature block (signature zones + eSign
  anchors) for the agreement's signer set, the added boilerplate clauses, and document furniture
  (agreement reference + page numbers); anchors are non-PII and stable.
- **signing-request** -- SHALL locate the rendered eSign anchors and map each to the provider's
  signature field when creating the signing request (anchor -> field), keeping `documents`
  eSign-agnostic.

## Signing-status FSM

**No new states.** The anchored execution block is produced at **generate-as-draft**
(`PDF_GENERATED`). Anchors are consumed at **create-signing-request** (`SIGN_REQUESTED`). Existing
transitions only.

## Async signing sequence (anchor -> eSign)

```
generate-as-draft            create-signing-request                 provider (Leegality/Digio, async)
  documents renders            signing reads anchors from the         places signature at each anchor
  draft PDF WITH               drafted artifact, maps each            -> returns signing URL
  esign anchors      ----->    anchor -> a provider signature   ----->  (webhook drives completion,
  (PDF_GENERATED)              field, submits the sign request         reconciliation is the fallback)
                               (SIGN_REQUESTED)                        -> signed PDF + audit annexure
```

## PII / security checklist

- **Introduces or moves PII / Aadhaar / OTP / VID?** No new PII field. The execution block renders
  signer name / address that are **already** in the draft; eSign anchors are **non-PII named markers**
  (`esign:owner`, not a person). The provider's audit annexure (masked Aadhaar, OTP result) is
  **provider-appended**, never rendered or logged by us.
- **Redaction:** anchors and the agreement reference carry no Aadhaar / OTP / VID / secret; nothing
  new is logged (draft bytes already never logged).
- **Secrets:** none introduced.
- **Sandbox + dummy data only:** preserved -- no live vendor credentials; anchors are exercised
  against the existing stub/WireMock provider.
