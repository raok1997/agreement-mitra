## Context

The templating platform (M0-M5 + selection/pin) is live and the TG template already captures a rich
clause set. The single functional gap before Aadhaar eSign is a **rendered execution / signature
block with anchors**. This design keeps the markup/data security boundary and the modulith boundary,
and leans on **template edits over code** wherever a change is just static clause text.

Constraints unchanged: Java 21 + Spring Boot 3.5 + Spring Modulith; keep `ModularityTests` green;
sandbox + dummy only; never log PII; users edit data, never template markup; deterministic +
version-pinned rendering (a signed agreement never re-renders against newer layers).

**Grounding (verified against the code, drives this refinement):**
- The production template set is `backend/src/main/resources/documents/template/sets/rental/`
  (`base.yaml` + `state-TG.patch.yaml` + `type-residential.patch.yaml` + the state_type patch).
  `examples/layers/` is a **test-fixture** set, not what a TG agreement renders.
- `RenderKind.SIGNATURES` exists and the base "In Witness Whereof" section already declares
  `render: signatures`; `TemplateCompiler.appendSignatures` renders a **generic** two-line block --
  no per-signer zone, no name, no anchor. So the engine work is to **extend an existing render**, not
  add a block type.
- `TemplateCompiler` is a **pure function** of `(effective template, data map, resolved date, active
  sections)` and holds **no party model**. Signer names arrive as flat keys `ownerName` /
  `tenantName` (`AgreementDocumentMapper`). Optional sections gate on **activeSections opt-in**, not
  on whether a field is filled.
- `signing` already holds the signer set with **roles** (`Role.OWNER` / `Role.TENANT`) via
  `AgreementResponse`; `SignRequest.Invitee` has no signature-field today.
- `docs/integrations/leegality.md` does **not** specify the anchor mechanism (text vs coordinate).

## Decisions

### D1: Template-first -- clauses are definition edits, only anchors need code
Boilerplate clauses (Notices, Governing law, Severability, Entire-agreement) are **system-owned
static text** added to `sets/rental/base.yaml` (`clauses:`) and wired into the "Now This Agreement
Witnesseth" clause list -- **no code**. The "IN WITNESS WHEREOF" wording is system-owned markup the
compiler emits from the signatures render (the section is already declared). Code is required only to
(a) **extend the existing `SIGNATURES` render** into per-signer zones and (b) **emit eSign anchors**.
**Alternative rejected:** hard-coding the block in the renderer bypassing the definition -- it would
skip the version pin. NOTE: the TG layer already ships a Telangana-specific `tgGoverningLaw`; keep the
national `governingLaw` general so the TG overlay is the more-specific statute, not a contradiction.

### D2: eSign anchors -- a role-derived token the compiler emits, `signing` derives + maps
Each signatory renders a **stable, non-PII anchor** `esign:<role>`. The compiler emits it as a
**detectable text token** in the rendered HTML (survives HTML->PDF so a text-anchor provider can find
it), with the role derived from the section entry's name field key (`ownerName` -> `owner`). `signing`
**derives the same `esign:<role>` token from the signer roles it already holds** and builds the
provider's signature-field list at create-signing-request -- it does **not** parse PDF text, so no
PDFBox / PDF-parsing dependency is added. `documents` stays **eSign-agnostic** (it knows "a signatory
zone exists," not "Leegality"); `signing` holds no `documents.template` type. **Refinement of the
original design**, which said `signing` "reads the anchors from the drafted artifact": because the
anchor is a deterministic function of role and both modules share the role vocabulary, deriving it is
equivalent, cheaper, and keeps the boundary -- while a literal PDF-text presence check remains a
possible follow-on guard. **Alternative rejected:** `documents` emitting provider-specific signature
fields -- leaks eSign into the domain-agnostic module.

### D2a: Signatory block shape -- section `entries` are the name field keys (no schema change)
The `render: signatures` section declares its signatories via its existing `entries` list: the signer
**name field keys** (`entries: [ ownerName, tenantName ]`). The extended `appendSignatures` iterates
them, renders one zone per entry (escaped name value + system-owned "Name as per Aadhaar" / date /
place labels), and derives the `esign:<role>` anchor from the key. This **reuses the existing
section/entries/fields model with zero definition-schema change** -- no new `signatories:` block, no
loader/validator/JSON-schema work. (Resolves the original "signatory block shape" open question.)

### D3: Witnesses -- an OPTIONAL section, opt-in via activeSections (default off)
Witnesses are **not required** for an 11-month, unregistered, Aadhaar-eSigned residential lease -- the
eSign audit trail is the attestation. They ship as a standard **optional add-on section** in
`sets/rental/base.yaml` (`optional: true`), fields `witness1Name` / `witness1Address` /
`witness2Name` / `witness2Address` defaulting empty. **Refinement of the original wording:** the
engine gates optional sections on **activeSections opt-in**, not on whether a field is filled, so the
witness lines render when the user **adds the Witnesses section**, exactly like every other optional
section (Charges & Utilities, Occupancy, Annexure). With the section not added -- the residential
default -- nothing renders. Witness details are **printed escaped data**, not eSign signers (no
anchor). Witnesses-must-eSign is a follow-on "additional signers" feature. **Alternative rejected:** a
static config / template flag -- inconsistent with how every other optional clause works.

### D4: Document furniture at the Gotenberg render layer (not the pure compiler)
A unique **agreement reference** (system-generated, non-PII -- the agreement id / a short ref) and
**page X of Y** are produced by the **Gotenberg render layer** (`GotenbergHtmlPdfRenderer` header/
footer template), NOT the pure HTML `TemplateCompiler` -- per-page furniture is a PDF-pagination
concern the compiler cannot see. The reference threads through `DocumentProjectionRequest` as render
metadata and ties the document to the eSign audit trail.

### D5: Modulith boundary
`documents` renders the block + anchors from the template definition + the flat data map (field key ->
value -- names arrive as `ownerName` / `tenantName`). It receives **no signing type**. `signing`
derives anchors from roles and maps them to provider fields, holding no `documents.template` type.
`ModularityTests` stays green.

### D6: Reproducibility
The execution block + anchors are part of the effective template, so they are covered by the existing
**version pin** (`AgreementDocumentService.pinEffectiveTemplate`) -- a signed agreement reproduces
byte-stable, anchors included. No new pin work.

## What is a template edit vs code (implementation split)

| Item | Template edit (`sets/rental/`) | Code (`documents` / `signing`) |
| --- | --- | --- |
| Notices / governing-law / severability / entire-agreement clauses | YES (base.yaml `clauses:` + section entries) | -- |
| "IN WITNESS WHEREOF" wording | section already declared | compiler emits it in the signatures render |
| Signatory zones (per-signer render) | declares `entries: [ownerName, tenantName]` | extend `appendSignatures` |
| eSign anchors | -- (derived from entry key) | compiler emits `esign:<role>` token; `signing` derives + maps it |
| Witness slots | declares fields + optional section | compiler renders when the section is opted in |
| Agreement reference + page X/Y | -- | Gotenberg header/footer + render metadata |

## Risks / Trade-offs

- **Anchor mechanism unknown per vendor** -- mitigated by the provider-agnostic anchor abstraction
  (D2) + the EsignProvider seam; the concrete translation lands in `LeegalityEsignProvider`,
  exercised against the stub/WireMock.
- **Role-derived vs artifact-read anchor** -- deriving from roles (D2) assumes the compiler and
  `signing` never diverge on the role vocabulary; both are `owner`/`tenant` today. If a future
  template emits anchors `signing` cannot predict from roles, add the literal PDF-text presence read.
- **Multi-party** -- today single-party-per-role; the zone loop iterates the entries / signer set, so
  multi-party is additive later (not in scope).
- **Legal correctness** (witnesses, L&L-vs-rental label, clause wording) -- owned by
  `rental-document-content-v2`, not this CR.

## Open Questions

- **Anchor mechanism** -- do Leegality / Digio place signatures by **text anchor** or **coordinates**?
  `docs/integrations/leegality.md` does not say; confirm with the vendor. Drives only
  `LeegalityEsignProvider`; the template-side `esign:<role>` token is unchanged either way.
- **Witness gating (confirm the refinement)** -- D3 now gates witnesses on **activeSections opt-in**
  (engine-consistent) rather than field-populated. Confirm this matches the intended UX, or keep a
  field-populated variant inside the signatures render.
- **National vs TG governing-law layering** -- confirm the general national `governingLaw` clause
  reads correctly alongside the existing TG-specific `tgGoverningLaw` (no contradiction / duplication).
