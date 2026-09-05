## Why

Users generate their agreement PDF from the capture screen's **"Download PDF"** button, and they read
the document in the on-screen **preview pane** (a sandboxed HTML iframe). Today neither shows any
provenance: the on-screen preview carries **no reference and no platform mark at all**, and a
reference only appears in the PDF's Chromium footer -- and only on the **saved-agreement** render path
(the raw 36-character UUID), never in the preview the user actually looks at.

Two problems:

- The reader sees nothing identifying the document on screen -- no reference number, no platform URL.
  The preview and the PDF disagree about what is on the page.
- After saving, the client has the raw agreement UUID but never surfaces a **readable tracking
  number**, so a user cannot note or quote their agreement's reference.

This change puts the document's identity **into the document body** so it renders identically in the
**on-screen preview and the PDF**, and surfaces the tracking number **to the client** after save:

- A short, human-friendly **tracking number** `AM-<LAST6>-<DDMMYY>` and the **platform URL** become a
  system-owned **provenance line at the foot of the compiled document body** -- so they appear in both
  the HTML preview pane and the PDF (byte-for-byte parity preserved, since both come from one
  compiler).
- **Page numbers** (`Page X of Y`) stay as Chromium print **furniture** (they cannot exist in a
  continuous HTML preview -- a scrolling document has no pages), stamped on every PDF page.
- The backend **exposes the tracking number to the client** (`AgreementResponse.trackingNumber`), and
  the frontend **displays it after save** and **passes it into the preview** so the post-save preview
  body shows the real number.

Every value is HTML-escaped; nothing new is persisted; there is no migration and no dependency change.

## What Changes

- **Provenance line in the compiled body (preview + PDF).** `TemplateCompiler` emits a system-owned
  provenance line at the document foot: `{tracking number} . {platform URL}`, both **HTML-escaped**.
  Because the live preview and the PDF are produced from the **one** compiler output, the line renders
  **identically** on screen and in the PDF (parity preserved). The two values are passed into `compile`
  as resolved inputs (like the execution date already is) -- the reference from the request, the URL
  from configuration -- so the compiler stays a pure function and the `documents` module keeps no brand
  literal.
- **Human-friendly tracking number (derived, not persisted).** On the saved-agreement render the
  reference is `AM-<LAST6>-<DDMMYY>` -- the uppercased last six hex characters of the agreement UUID
  and the agreement's start date (the "Agreement date" on the document), **derived at render time**
  from data the aggregate already holds. The **full UUID stays the canonical audit identifier**; the
  number is a display-only veneer (its last-six-hex fragment is 24 bits, so it is **not collision-free**
  and is never used as a key).
- **Page numbers stay PDF furniture.** `GotenbergClient`'s footer furniture is reduced to **page
  numbers only** (`Page X of Y`), stamped on every PDF render. The reference + URL are no longer in the
  furniture (they moved to the body), so the PDF shows each exactly once in the right place -- the
  provenance line in the body, the page number on every page.
- **Tracking number exposed to the client.** `AgreementResponse` gains a `trackingNumber` field
  (derived server-side, authoritative -- the client does not re-derive it), so after create/get the
  client holds the same number the document shows.
- **Frontend surfaces + previews it.** After a successful save the capture flow **displays the tracking
  number** on the save confirmation and **passes it into the preview request**, so the post-save preview
  body shows the real number. Before save (no agreement yet) the preview shows the platform URL and a
  `PREVIEW - NOT FOR EXECUTION` marker in place of a number.

## Capabilities (Modified: template-document-projection, document-rendering, agreement-management)

- **template-document-projection (compiler).** ADDED -- the compiler emits a system-owned provenance
  line (tracking reference + platform URL) at the document foot, HTML-escaped, in both the preview HTML
  and the PDF's HTML source, from the one compiler (parity). The reference is bound under a reserved
  data key and the URL is a resolved input; a blank reference/URL omits its part.
- **document-rendering (render seam / furniture).** MODIFIED -- the Chromium footer furniture is reduced
  to **page numbers only** (`Page X of Y`), stamped on every PDF render; the reference and platform URL
  are no longer furniture (they are body content now).
- **agreement-management (API).** ADDED -- `AgreementResponse` exposes a display-only `trackingNumber`
  derived from the agreement id + start date; the raw id remains the canonical identifier.
- **Frontend (capture / preview).** The capture flow displays the tracking number after save and feeds
  it into the preview so preview and PDF agree.

## Impact

- **`documents` module.** `TemplateCompiler` gains a system-owned provenance-line emitter fed by a
  resolved reference + URL (new `compile` inputs, like the execution date); `DocumentProjectionService`
  resolves the platform URL (config) and threads the request's reference into `compile` for **both**
  the preview and PDF faces (parity). `GotenbergClient` furniture drops to page numbers only, applied on
  every PDF render. The public `documents.api` request record keeps `documentReference` as the reference
  input. `ModularityTests` stays green (URL is app config, no brand literal in module code).
- **`signing` module.** `AgreementDocumentService` builds the tracking number and passes it as the
  reference; `AgreementResponse` + its mapper expose `trackingNumber` (derived, not stored).
- **Frontend.** The capture view captures `trackingNumber` from the create response, shows it on the
  save confirmation, and includes it in the preview request; a small api-client change carries the
  field. Frontend unit test added.
- **Configuration.** One additive non-secret property (`documents.footer.platform-url`,
  `agreementmitra.com` in `application.yml`, env-overridable).
- **Coordination with `agreement-execution-block` (active, not yet archived).** That CR introduced the
  Chromium footer (reference + page numbers). This change **moves the reference + URL into the body** and
  reduces the furniture to page numbers; whichever archives second reconciles the footer requirement.
- **No change to:** the DB schema (no migration), the effective-template identity/pin (the provenance
  line is system-owned compiler output, not template-content-hash input), the signing FSM, the
  eSign/webhook flow, stamping, object storage, statelessness of the preview (still persists nothing,
  still `no-store`), or any dependency (no `gradle.lockfile` change; nothing new on the OSV/SpotBugs
  surface).

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** **None.** The provenance line carries a
  UUID-fragment + a calendar date (the tracking number) and a public brand domain (the platform URL);
  `trackingNumber` on the response is the same non-PII derived value. No Aadhaar number, OTP, virtual
  id, biometric, government identifier, or secret is added, logged, or persisted.
- **How redacted/secured?** Every provenance value is **HTML-escaped** at compile time (the same
  markup/data boundary all body text already crosses), so neither the reference nor the URL can inject
  markup or active content. The tracking number exposes only the **last six** hex digits of the id (not
  the full id, not any PII); the full UUID stays the internal audit tie. The render stays **offline**
  (the URL is inert display text -- no anchor, no fetch -- and Gotenberg's outbound network stays
  denied). The composed HTML and submitted values are still never logged; the stateless preview still
  persists nothing and stays `Cache-Control: no-store`.
- **Collision note (evaluated).** The tracking number's last-six-hex component is 24 bits, so it is
  **not** collision-free and is **display-only**; the authoritative artifact-to-audit tie remains the
  full agreement UUID (design D2).
- **Parity preserved.** One compiler still produces both the preview HTML and the PDF's HTML source from
  the same inputs (now including the provenance line), so the previewed document is byte-for-byte the
  signed document's HTML source.
- **Sandbox + dummy data only?** Preserved -- no live provider, credential, or secret env var is added.
- **Signing-status FSM transitions touched?** **None.**
- **Async signing / webhook flow touched?** **None** -- no sequence diagram required.
