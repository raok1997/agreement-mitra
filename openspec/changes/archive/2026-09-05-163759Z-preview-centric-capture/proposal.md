## Why

Today's capture is a **sequential form**: fill Tenant -> Owner -> Property -> Create -> scroll down
-> Preview -> Save. The user never sees the document until the end, and every new capability (custom
conditions, template choice, clause library, more parties) makes the form **longer and more
intimidating**. For a **legal document**, the strongest trust and clarity lever is letting the user
**see the agreement fill in as they enter data**.

This change reimagines capture as **preview-centric**: the rendered agreement is the centrepiece,
and each data area (Tenant, Owner, Property, Custom Conditions, and a growing catalog of sections)
is an **edit control around the preview**. Clicking a section opens a focused modal; on save, the
**preview refreshes** to the latest agreement. It is the container every future document feature
plugs into cleanly, instead of extending a linear form.

**Relationship to other work:** this is the **UX/interaction layer** for the `documents` module; the
**capability layer** (multi-template, clause library, state/language) lives in the
`document-templating-platform` exploration. This change **absorbs the parked CR-3d (custom
conditions)** -- custom conditions become one section in the preview-centric shell rather than a
standalone feature. It builds directly on CR-3a/b/c (render, preview, generate-as-draft).

## Design summary (see design.md for detail)

- **Responsive, not "PDF canvas everywhere":** two panes on desktop (sections left, live preview
  right); on mobile the guided sections stay primary with the live preview one tap away and
  full-screen section sheets.
- **Live preview = assembled HTML (instant, same template); PDF = fidelity/final.** The live pane
  renders the **same Thymeleaf template** as HTML (no Gotenberg round-trip, no template divergence),
  updating on every section save; the Gotenberg PDF backs "Download PDF" and the final generated
  draft.
- **Stateless preview from an in-progress working set:** the browser holds the working draft; a
  **stateless preview endpoint** renders **partial** data (placeholders for what is missing) with
  **no persistence**. Nothing is saved until the deliberate "Save & continue" (existing
  create + generate-as-draft).
- **Pluggable section registry:** each section is `{id, label, modal fields, template binding,
  completeness rule}`, so adding a section is cheap and consistent -- the architecture that makes the
  catalog below tractable.

## The section catalog (what surrounds the preview)

Sections/controls the user can add or edit. `[P1]` = first-wave "custom conditions" starter set
(structured knobs = escaped data, the highest-value, lowest-risk additions); the rest are phased.

### A. Core parties & property (exists today, re-homed into modals)
- Tenant Details, Owner Details, Rental Property Details.

### B. Financial terms (most-negotiated)
- `[P1]` Rent due date + payment mode (UPI/bank/cheque)
- `[P1]` Rent escalation (% per year / on renewal)
- `[P1]` Late-payment penalty
- `[P1]` Security-deposit terms (# months, refund timeline, deduction rules)
- `[P1]` Maintenance charges (who pays society/upkeep, amount)
- `[P1]` Utilities split (electricity/water/gas/internet -- who pays, meter basis)
- `[P1]` Painting/cleaning charges at exit
- Brokerage (amount, who bears); computed rent-schedule / payment table + total contract value.

### C. Tenancy rules & occupancy
- `[P1]` Lock-in period; `[P1]` Notice period; `[P1]` Permitted occupants (number/names)
- `[P1]` Pets policy; `[P1]` Parking (2-/4-wheeler, slot)
- Subletting allowed/not; Use of premises (residential/mixed/home-office); Entry & inspection
  notice; Renewal terms.

### D. Property detail expansion
- Type (apartment/independent house/PG/villa), BHK, carpet/built-up area, floor
- `[P1]` Furnishing status (furnished/semi/unfurnished)
- `[P1]` Fixtures & inventory list -> a Schedule/Annexure (the #1 deposit-dispute preventer)
- Amenities (lift, power backup, security, water source); possession/handover date.

### E. Parties beyond the basic two
- Multiple tenants / co-tenants and multiple owners / co-owners (joint ownership)
- Guarantor / surety; Broker / agent (and agency letterhead/logo for B2B); Nominee / emergency
  contact.

### F. Legal & clause controls (-> clause library, CR-3e)
- Optional clause toggles from a vetted library (indemnity, force majeure, alterations, insurance)
- Dispute resolution (arbitration/mediation + seat/city), governing law
- Registration & stamp-duty responsibility (who bears it)
- Free-text custom clauses (the classic "custom conditions").

### G. Document-format controls (-> template catalog CR-2 + rules engine CR-4)
- Template choice (residential/commercial/PG/leave-&-licence)
- State/jurisdiction (drives stamp duty, registration threshold, mandatory clauses)
- Language (English/Hindi/Telugu/Marathi, or bilingual side-by-side)
- Agreement date & place of execution; e-stamp details; annexures.

### H. Preview-experience controls (view, not document content)
- Download PDF / print; highlight-what's-missing toggle; language toggle; "what changed" indicator.

## Phasing (small, shippable increments)

- **Phase 1 (this change's implementable scope):** the **preview-centric shell** (responsive two-pane
  / mobile toggle), the **stateless partial-preview endpoint** (HTML + PDF, not persisted), the
  **partial-tolerant template** (placeholders for empty sections), section modals for the **existing**
  Tenant/Owner/Property data, a **completeness status bar**, and **Download PDF** + final "Save &
  continue" wired to the existing create + generate-as-draft. No schema change (preview is driven by
  the client working set; only the final save persists, using today's fields).
- **Phase 2 -- custom-conditions starter set (`[P1]`):** the structured financial/tenancy knobs +
  furnishing/fixtures, as sections + escaped-data preview; persistence via a flexible
  agreement-attributes store. (Absorbs CR-3d.)
- **Phase 3+:** additional parties (E), clause library (F = CR-3e), template/state/language
  (G = CR-2/CR-4), computed schedules, annexures.

## Capabilities

### New Capabilities
- `preview-centric-capture`: a responsive, document-first capture experience -- a live preview
  rendered from an in-progress working set, section-based editing via modals, a completeness
  indicator, and a stateless partial-data preview (not persisted). Persistence stays the deliberate
  final step (existing create + generate-as-draft).

### Modified Capabilities
- `document-rendering`: the renderer SHALL tolerate **partial/incomplete** data (render placeholders
  for absent sections rather than fail) and SHALL expose the composed **HTML** (not only the PDF) for
  a fast, embeddable live preview -- the same trusted template, still escaped and offline.

## Impact

- **Backend:** a stateless preview endpoint (`POST /api/agreements/preview`, body = in-progress data
  -> inline PDF; a content-negotiated HTML variant for the live pane), reusing the renderer with a
  partial-tolerant DTO->data-map mapper that is **single-sourced with the persisted mapper** (parity
  test) so preview and the signed PDF can't diverge; template `th:if` placeholders; **Noto faces
  embedded** in the HTML pane for complex-script fidelity. The in-progress DTO is a **distinct** record
  that relaxes required-ness but **keeps size/length bounds**. `signing` still depends only on the
  `documents` public interface.
- **Frontend:** a substantial rework of the capture screen -- a working-data store, the responsive
  two-pane/mobile shell, a section-registry + modal pattern, the completeness bar, live-HTML preview
  embed, and Download-PDF, built to the **locked reference UI**
  (`assets/preview-centric-mockup.html`; see design D5). API calls stay in `src/api/client.ts`.
- **No DB schema change in Phase 1** (preview is client-driven; final save uses today's fields).
  Later phases add a flexible agreement-attributes store for the new section data.
- **No** change to the signing FSM, stamping, or the eSign/webhook flow.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** The preview (HTML and PDF) **contains full
  party PII**, now composed from an **in-progress, unsaved** working set. No Aadhaar number, OTP,
  virtual id, or government identifier is added; no secrets introduced.
- **How redacted/secured?** The stateless preview **persists nothing server-side** and is served
  **`no-store`**; bytes/HTML **and validation-error field values** are **never logged**. All working
  data stays **HTML-escaped** (the markup/data boundary holds -- users edit data, never template
  markup); the render stays **offline** with Gotenberg's outbound network denied. The live-HTML pane
  is embedded in a **sandboxed iframe** (no scripts; our template has none) so escaped data cannot
  execute, and the HTML variant carries a `Content-Security-Policy` + `nosniff`.
- **Two exposures this change *does* add, and their controls:**
  - **Client-side PII at rest.** If localStorage backs refresh-resume, the working set (names,
    father's names, addresses) sits unencrypted in the browser — a real risk on shared/kiosk devices.
    "Persists nothing" is a **server-side** statement. Controls: **clear on Save & continue and on
    reset**, plus a short TTL (tasks 3.1/6.3).
  - **Unauthenticated render abuse.** `POST /api/agreements/preview` renders from an anonymous body
    with no persisted-agreement precondition — the cheapest render to abuse. Controls: the in-progress
    DTO **retains its size/length bounds** (only required-ness relaxed), and **rate-limiting is an
    owed companion before this leaves sandbox**.
- **Sandbox + dummy data only?** Preserved -- local render of dummy in-progress data; no persistence
  of a preview, no live provider, no production credentials.
- **Signing-status FSM transitions touched?** **None** -- capture/preview precede signing.
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
