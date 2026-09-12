# Flow Journal -- agreement-execution-block

> **Purpose.** Development-kickoff handoff for the **execution / signature block + eSign anchors** CR
> -- the one functional gap before Aadhaar OTP eSign. Read `CLAUDE.md` + this CR's `proposal.md` +
> `design.md`, then implement per `tasks.md`. Backend runs on `:8090` (local profile); the TG
> template already captures a rich clause set -- this CR fills the empty "In Witness Whereof" section
> and makes the draft signable.

## 1. Where this sits in the end-to-end flow

```
  pick (State x Type)  ->  schema-driven form  ->  live preview  ->  Save & continue
        (done)                 (done)                (done)         (create + select template, done)
                                                                          |
                                                                   generate-as-draft
                                                                   documents renders the draft PDF
                                                                   >>> TODAY: ends at dispute clause,
                                                                       "In Witness Whereof" empty <<<
                                                                          |
                                       THIS CR: render execution block + eSign anchors + boilerplate
                                                + agreement ref/page numbers   (PDF_GENERATED)
                                                                          |
                                                                   create-signing-request
                                       THIS CR: signing reads anchors -> maps to provider signature
                                                fields, submits (SIGN_REQUESTED)
                                                                          |
                                     SUBSEQUENT MODULE (not this CR): Aadhaar OTP eSign via provider,
                                     webhook/reconciliation drive completion -> signed PDF + audit
                                     annexure (SIGNED)
```

This CR produces the **anchored signable artifact**; the actual OTP eSign is the next module.

## 2. What is already done (do NOT rebuild)

Verified live (`GET /api/templates/form?state=TG&type=residential`, 37 fields / 11 sections): the TG
template already offers Owner/Tenant/Property, Term (incl. lock-in, notice), Financial (incl.
escalation, payment mode), **Charges & Utilities** (maintenance, utilities, late-penalty), **Occupancy
& Use** (occupants, pets, parking, permitted-use), **Dispute** (+ free-text special conditions),
**Annexure** (fixtures), **Statutory (Telangana)** (stamp duty, registration charges). The rich clause
set is NOT the gap -- do not re-add it.

## 3. The gap = what this CR does

| # | Item | Kind | Where |
| --- | --- | --- | --- |
| 1 | Execution / signature block (signature zones) | **Engine** (new signatory block render) | `documents` compiler |
| 2 | eSign anchors (`esign:owner`, `esign:tenant`) | **Engine** | `documents` compiler + `signing` map |
| 3 | Witnesses (optional add-on section, default off) | **Template edit** (optional fields) + render-when-filled | base.yaml + compiler |
| 4 | Boilerplate clauses (notices, governing-law, severability, entire-agreement) | **Template edit** | `base.yaml` |
| 5 | "IN WITNESS WHEREOF" wording | **Template edit** | `base.yaml` |
| 6 | Agreement reference + page X of Y | **Engine** (render/header-footer) | `documents` render |
| 7 | Anchor -> provider signature-field mapping | **Engine** | `signing` (EsignProvider) |

**Template-first:** #4 and #5 are definition edits (no code). Only #1/#2/#3/#6/#7 need engine work.

## 4. Contracts / boundaries (keep these)

- **Markup/data boundary:** signer values in the execution block are **escaped**; the block + clauses
  are system-owned template markup. Users never author markup.
- **Modulith:** `documents` renders the block + emits anchor tokens from the definition + a plain
  signer data map (role + display name); it holds **no `signing` type** and is **eSign-agnostic**.
  `signing` maps `esign:<role>` -> provider signature fields and holds **no `documents.template`
  type**. `ModularityTests` must stay green.
- **Anchor abstraction:** the anchor is a stable, non-PII role marker. The concrete provider
  translation (text-anchor vs coordinate) lives in the **EsignProvider adapter**, exercised against
  the **stub / WireMock** provider -- no live credentials.
- **Reproducibility:** the block + anchors are part of the effective template -> covered by the
  existing **version pin**. No new pin/migration work. No schema change.
- **FSM:** no new state -- produced at `PDF_GENERATED`, consumed at `SIGN_REQUESTED`.

## 5. Coordination -- what this CR does NOT own

- **Formatting** (ISO dates -> "1 July 2026"; amounts in words + Indian grouping; "5th day"; clause
  numbering restart) -> `rental-document-content-v2` (already in legal review).
- **Legal decisions** (include witnesses? "Leave & Licence" vs "Rental Agreement" label for TG; exact
  clause wording) -> `rental-document-content-v2` legal review. This CR renders the *capability*;
  legal review sets the *defaults*.
- **Actual eSign OTP integration** (provider call, OTP, signing URL, webhook) -> the subsequent
  signing module. This CR stops at the anchored artifact + anchor->field mapping against the stub.
- **TG e-stamp** integration -> a separate stamp-provider CR (today's stamping is synthetic Karnataka;
  a TG agreement needs TG-appropriate stamping).
- **Brokerage / renewal** fields -> deferred optional add-on sections.

## 6. Open decisions (resolve during apply)

- **Anchor mechanism** -- do Leegality / Digio place signatures by **text anchor** or **coordinates**?
  Check `docs/integrations/leegality.md`; the template-side anchor is unchanged either way -- only the
  adapter differs.
- **Witness default** -- DECIDED (D3): an optional add-on section, default off, renders only when
  filled. Legal review only confirms whether an added witness must also eSign (a follow-on).
- **Signatory block schema** -- the exact template-definition shape for the `signatories` / execution
  block (per-signatory fields, anchor id from role).

## 7. Kickoff checklist

1. Read `CLAUDE.md`, this CR's `proposal.md` + `design.md`, and `tasks.md`.
2. Backend/infra is up (compose Postgres/MinIO/Gotenberg; backend on `:8090`, local profile). Windows:
   run tests via gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true` and
   `-Duser.timezone=Asia/Kolkata`.
3. Start template-first: §1 clause edits in `base.yaml`, then §2-§4 engine work.
4. Ship unit + integration tests (§5). Keep `ModularityTests` green; keep the markup/data + eSign-
   agnostic boundaries.
5. Live-verify on `:8090` (§6.1): generate a TG draft and confirm the execution block + anchors +
   boilerplate + agreement ref render, and the "In Witness Whereof" section is filled.
