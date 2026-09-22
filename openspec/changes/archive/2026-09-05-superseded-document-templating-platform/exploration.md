# Exploration: Document Templating Platform

> **Status: EXPLORATION / EPIC -- not a directly-applyable change.** This captures the agreed
> vision for evolving the `documents` module from "one bundled rental-agreement template" into a
> generic, multi-template, customizable rental-agreement platform. It is decomposed into small,
> independently-shippable CRs (below); each is proposed, reviewed, applied, validated, and archived
> on its own. This folder is the parent/vision; it spawns those CRs and is not itself implemented.

## Why

The guided flow renders exactly one bundled rental-agreement template (CR-3a/CR-3b). Real rental
needs are diverse: different agreement types (residential / commercial / PG / leave-&-licence),
state-specific legal language, per-deal custom conditions (lock-in, notice, escalation, pet policy),
and -- for the AgreementMitra team -- the ability to author and manage the template library itself.
This exploration sets the target shape and the order to get there without over-building or opening a
security hole.

## The organizing principle: markup vs. data is a SECURITY boundary

The current safety model -- and the one every idea here must respect -- is a single invariant:

> **The template markup is system-owned and trusted; ALL user-supplied input is escaped data.**

That invariant is what makes rendering legal PDFs safe (Thymeleaf auto-escaping + Gotenberg's
network-deny). Every capability below is sorted by which side of the boundary it lands on:

| Capability | Who owns the markup | Injection surface | Verdict |
| --- | --- | --- | --- |
| Pick from **curated** templates | System (legal-reviewed) | none | End-user, early |
| **Custom conditions** (knobs + free text) | System markup; user **data** (escaped) | none new | End-user, early |
| Toggle **vetted optional clauses** | System | none | End-user, early |
| **State / language** template variants | System | none | End-user, with rules engine |
| **Raw user-authored templates** | *User* -> untrusted markup | **RCE** (Thymeleaf SpringEL), HTML/CSS injection, SSRF | **Never as raw markup** |

Key consequence: **users never write template markup.** Authoring (a real need for the team) is
delivered as a **structured builder** -- the author manipulates structured building blocks (sections,
clause-library entries, typed variables) that the system **compiles** into a safe, system-owned
template. The user manipulates data; the system owns the markup; the boundary holds. Authoring is
therefore an **Admin-only** capability (site admin / legal team), gated behind auth + a role -- not
an end-user feature.

## Capability split (agreed)

**End-user facing** (anonymous or logged-in tenant/owner):
- Choose one among **multiple curated templates** (by type / state / language).
- Add **custom conditions**: structured knobs (lock-in, notice period, rent escalation, deposit
  terms, parking, pets, furnished/unfurnished, occupants) + free-text **additional clauses**.
- Toggle and reorder **vetted optional clauses** from the clause library.
- Get the right template **suggested by property state / language**.

**Admin-only** (site admin / legal team -- behind `mobile-otp-auth` + an admin role):
- **Author and manage templates** via the structured builder (sections + clauses + variables).
- Curate the **clause library** (add / edit / retire vetted clauses).
- Manage the **template lifecycle**: draft -> legal-approved -> published -> deprecated, with
  immutable versions.

## Template definition & resolution model (the backbone)

The unifying idea underneath every capability above: **a template is a declarative definition,
resolved from the few dimensions the user picks, and both the capture form and the rendered document
are two projections of that same resolved definition.**

    dimensions (user picks)         resolve                 effective template           project
    State x Type              -->   (layered compose)  -->  fields + clauses + sections  -->  { capture form ,  document (HTML -> PDF) }
    (Language reserved -- English only for now)

One source of truth (the effective template) drives two faces: the **capture form** the end-user
fills, and the **document** that is rendered and signed. The section rail in the capture UI is
therefore **generated from the resolved template's field schema** -- never hardcoded (the current
`preview-centric-capture` mockup hardcodes it only to prove the UX; it is a stand-in for this
projection).

### Dimensions

A template is selected by a small, **extensible** set of dimensions. **Active now:** **state /
jurisdiction** and **rental type** (residential / commercial / PG / leave-&-licence) -- the user picks
these and the system resolves + adapts the form + document. **Language is designed in as a dimension
but deferred -- English only for now**: the axis stays in the model so Hindi / Telugu / bilingual
variants drop in later (per go-to-market) without a redesign. New axes later (city, urban/rural) are
likewise just additional dimensions.

### Layered composition -- how "master template + inherit" works  [DECIDED]

A template is **not** a single-parent `extends` chain (that hits diamond conflicts the moment state
and type both contribute). Instead the effective template is **composed by layering patches in a
fixed precedence**:

    national base  <-  type layer  <-  state layer  <-  state+type layer   [ <-  language layer -- deferred ]

Each layer is a patch that can **add / replace / remove / reorder** sections and clauses, and
**override field metadata** (required, default, options, validation). Last layer wins on conflict.
Resolution is a **deterministic pure function of (dimensions, layer versions)**. `extends:`-style
sugar may be exposed to authors, but it resolves to layered patches underneath. Adding a new
jurisdiction = authoring one small state layer over the shared base, not copying a whole template.

### The definition format  [DECIDED: YAML authoring -> canonical JSON]

Templates are authored in **YAML** (humans read and diff it; XML is noisy, raw JSON is unfriendly)
and compiled to a **canonical, JSON-Schema-validated JSON** form that is immutable and versioned. A
definition has three parts:

- **`fields`** -- the typed variable schema (the data contract). Each field carries `key, label,
  type, required, default, options, validation, group`. This drives the **dynamic capture form** and
  its validation. **Mandatory / optional / default live here**, and any layer can override them
  (e.g. a state+type layer marks `registrationResponsibility` required).
- **`clauses`** -- reusable legal text, either `ref:`d from the clause library or inline, as **plain
  text with typed `{{slots}}`** bound to declared fields (never HTML, never code). An optional
  `showWhen` gates a clause on field values.
- **`sections`** -- ordered grouping of fields/clauses; defines both the form groups and the
  document body order.

```yaml
meta:    { id, dimensions: { state, type }, version, status, layers: [ ... ] }   # language: English-only for now
fields:
  - { key: rent,         label: Monthly rent, type: money, required: true }
  - { key: lockInMonths, label: Lock-in,      type: int,   required: false, default: 6 }
clauses:
  - ref: clause-lib/late-payment                       # pulled from the clause library
  - { id: escalation, text: "Rent increases by {{escalationPct}}% {{basis}}.",
      showWhen: "escalationPct > 0" }                   # safe expr over declared fields only
sections:
  - { title: Financial terms, clauses: [ rent, deposit, escalation, late-payment ] }
```

### Generic for authors, simple for end-users

The complexity lives with **template authors** (legal team, **admin-only**): YAML, layers, clause
library, versioning -- gated behind auth + review. **End-users never see any of it**: they pick
State x Type, then fill a generated form with defaults pre-filled, mandatory fields first,
optional sections tucked away. Simplicity is a *product* of the resolver hiding the machinery -- the
same definition that gives authors power gives users a short, relevant form.

### Preview generation -- three layers, two of them cacheable  [DECIDED]

How the preview screen is produced from a template. Split by what depends on user data:

    (1) RESOLVE            (2) FORM PROJECTION          (3) DOCUMENT PROJECTION
        state x type            effective template           effective template + USER DATA
            |                        |                            |
        effective template     capture-form schema          rendered HTML / PDF
        (data-independent)      (data-independent)           (data-dependent)
        --> cached per version  --> cached per version       --> generated ON THE FLY, per edit

- **(1) Resolve + (2) form structure are data-independent -> pre-computed and cached** per
  `(state, type, template-version)`. Resolving the layered patches and deriving which fields/sections
  show does not need the user's data; do it **once** when dimensions are picked, then reuse.
- **(3) The filled document is a function of the user's in-progress data -> generated on the fly.** It
  **cannot** be pre-generated -- the content does not exist until the user enters it. The template
  *scaffold* is pre-generated; the *filled document* is live.

**Two render tiers (no Gotenberg in the keystroke loop):**

- **Live HTML preview** -- on every section save (debounced), the **template compiler assembles
  escaped HTML** (fill slots, evaluate `showWhen`, escape). Cheap (tens of ms). This is the live pane.
- **PDF (fidelity / final)** -- **Gotenberg** (HTML -> PDF) runs only on explicit "Download PDF" and
  the final "Save & continue" (generate-as-draft). Never per keystroke.

**One renderer, non-negotiable:** the stateless preview endpoint assembles HTML from the **same
compiler that produces the signed PDF** -- never a separate client-side renderer that can drift, so
the document a user previews is the document they sign (guarded by a parity test). Preview is
stateless / `no-store` / nothing persisted. Only the final commit persists **and pins the resolved
effective-template hash + version**; previews are ephemeral and never pinned.

### Safety guardrails carried by the model

- **`showWhen` uses a small sandboxed boolean DSL over declared fields only -- never Thymeleaf
  SpringEL** (that is the RCE surface). Our engine evaluates conditions, not the template engine.
- Clause slots are **escaped at compile time**; authored markup stays system-owned. The markup/data
  boundary holds -- authors manipulate a structured definition, not HTML.
- **Deterministic resolution + version pinning**: an agreement records the resolved effective
  template (hash + layer versions) it used; a signed agreement is **never re-resolved** with newer
  layers.

## Target architecture (where the module is heading)

- **Resolution / composition engine** (the backbone above): resolves `(state, type)` -> layered
  patches -> one immutable **effective template**, then projects it into the capture-form schema and
  the document render. Deterministic and version-pinned. (Language is a reserved dimension, English
  only for now.)
- **Template registry** (Postgres): `{id, name, description, category, state, language, version,
  status}`. Bodies live as resources / object storage, **never inline in Postgres**. The
  domain-agnostic `DocumentRenderer` stays as-is; a template-resolution layer selects the body.
- **Template variable schema**: each template **declares the variables it needs**. That schema
  drives **dynamic capture-form fields** and validation, and decouples templates from the fixed
  `Agreement` columns -- new templates can request fields today's schema lacks (amenities,
  occupants) via a flexible **agreement-attributes** store, no migration per template.
- **Clause library**: reusable, legal-reviewed clauses `{id, title, category, body-with-variables,
  jurisdiction}`. A template = base + selected clauses; numbering auto-managed. Custom free-text
  clauses are **escaped data**, appended as text.
- **Structured builder -> compiled template**: the admin builder emits a **template definition
  (JSON)**, not HTML; the renderer turns definition + data into the system-owned HTML. Preserves the
  markup/data boundary.
- **Versioning & reproducibility** (non-negotiable for legal infra): published versions are
  **immutable**; an agreement **records the template id + version it used**; a signed/executed
  agreement is **never re-rendered with a newer template**. Effective-dating for law changes.
- **State / language**: state-driven template suggestion + stamp-duty / registration requirements
  from the parked **rules engine (Drools)**; Indic-language variants (the Gotenberg + Noto stack
  already renders them -- only translated bodies + bilingual layout are needed).

## Roadmap (decomposition into CRs)

| CR | Scope | Audience | Risk | Value | Depends on |
| --- | --- | --- | --- | --- | --- |
| **CR-3c** (next) | Generate & store the render as the signable draft | End-user | Low | High | CR-3b |
| **CR-3d -- Custom conditions** | Structured knobs + free-text additional clauses (escaped data) | End-user | Low | High | CR-3b |
| **CR-3e -- Clause library** | Toggle / reorder vetted optional clauses | End-user | Low | High | CR-3d |
| **CR-2 -- Template catalog** | Registry + selection + per-template variable schema -> dynamic form; record template+version | End-user | Med | High | CR-3e |
| **CR-4 hooks -- State/language** | State-driven selection, stamp/registration, Indic variants | End-user | Med | High | catalog + rules engine |
| **Admin authoring** | Structured builder, clause/template management, versioning workflow | **Admin only** | High | Med | catalog + `mobile-otp-auth` (admin role) |

**Recommended order:** CR-3c (finish the current arc) -> **CR-3d custom conditions** (cheapest big
win, pure escaped data, no boundary change -- makes the single template feel bespoke) -> CR-3e
clause library -> CR-2 catalog -> CR-4 state/language hooks -> Admin authoring last (it needs the
catalog, versioning, and admin auth to exist first).

The **template-definition & resolution engine** (see the backbone section) is what **CR-2 introduces**
-- the registry, the YAML definition + layered resolver, and the field-schema -> dynamic-form
projection. CR-4 (state/language) and Admin authoring both build on it: CR-4 adds jurisdiction/language
*layers*, and the admin builder emits *definitions* into the same registry. CR-3d/CR-3e stay
deliberately ahead of it as escaped-data-only increments that don't yet need the engine.

## Non-negotiables carried into every CR

- **Markup/data boundary:** users never author raw markup; custom input is escaped data; admin
  authoring goes through the structured builder that compiles to system-owned markup.
- **Offline render:** self-contained HTML; Gotenberg outbound-network denied (SSRF/exfil guard);
  Noto fonts bundled.
- **PII discipline:** rendered PDFs (party PII) are never logged; previews stay `no-store`; nothing
  persisted on preview.
- **Reproducibility:** template+version pinned on each agreement; executed agreements never
  re-rendered with a newer template.
- **Modulith boundaries:** `documents` stays domain-agnostic (template id + data map); `signing`
  owns the agreement -> data mapping and depends only on the `documents` public interface.

## Decisions locked (this exploration)

- **Inheritance = layered composition** (fixed precedence: base <- type <- state <- state+type <-
  language), not a single-parent `extends` chain. See the model section above.
- **Definition format = YAML authoring, compiled to canonical JSON-Schema-validated JSON**; immutable
  + versioned. The variable schema *and* the admin structured builder both target this one definition
  shape -- no raw HTML passthrough.
- **Preview = pre-resolved scaffold + live document render** (three layers, two cacheable): resolve +
  form schema are cached per template version; the filled document renders on the fly via the *same*
  server compiler that produces the signed PDF (single renderer, parity-tested). Gotenberg is deferred
  to explicit PDF / final commit -- never per keystroke.

## Open questions (to resolve per CR)

- **Clause library storage** -- Postgres rows vs versioned resource files; how jurisdiction scoping
  interacts with the rules engine and the layer precedence.
- **Condition DSL scope** -- the exact grammar/operators of the sandboxed `showWhen` expression
  language (comparison + boolean only? membership tests?) and where it is evaluated in the pipeline.
- **Admin role model** -- how the admin role rides on `mobile-otp-auth` (separate admin identities?
  claims? allow-list?).
- **Which states first** -- driven by go-to-market (see `docs/ROADMAP.md`, Hyderabad first ->
  Telangana / AP templates). **Language stays English** until go-to-market actually needs Telugu /
  bilingual, at which point the reserved language dimension is activated.
- **Caching per version (deferred from `template-catalog` 11.2)** -- the locked "pre-resolved scaffold,
  cached per version" decision is **not yet implemented**: `FormSchema.contentHash` exists as the
  intended cache key / HTTP `ETag` but no server cache or `ETag`/`If-None-Match` (304) wiring exists on
  the form or catalog controllers (resolution is deterministic, so this is efficiency, not
  correctness). Deferred to a **dedicated caching CR** that wires `contentHash` -> `ETag`/304 across the
  form + catalog read endpoints (the catalog DTOs carry no hash today, so it would hash the response or
  borrow the resolved template's hash). Not done in `template-catalog` to avoid touching the M3-owned
  form controller mid-flight.
