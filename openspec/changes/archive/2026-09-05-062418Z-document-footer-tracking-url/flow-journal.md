# Flow Journal -- document-footer-tracking-url

> **Purpose.** Development-kickoff handoff for the **document provenance line (tracking number + URL)
> in the body + page numbers in PDF furniture + tracking number surfaced to the client** CR. Read
> `CLAUDE.md` + this CR's `proposal.md` + `design.md`, then implement per `tasks.md`. No DB migration,
> no dependency change. Backend on `:8090` (local profile); frontend via `npm run dev`.
>
> **Design pivot (2026-07-14):** an earlier interim implementation put the tracking number + URL in the
> PDF-only Chromium **furniture**. Decision changed: the reader must see the reference + URL in the
> **on-screen preview** too, so they move into the **compiled document body** (preview + PDF, parity).
> Page numbers cannot exist in a continuous HTML preview, so they stay furniture (PDF only). The
> furniture-only code in the working tree is superseded by this plan.

## 1. Where this sits in the end-to-end flow

```
  pick (State x Type) -> form -> live HTML preview (iframe srcdoc)  ---- Download PDF
                                   |                                       |
                                   |  body provenance line renders in      | same body -> Gotenberg PDF
                                   |  BOTH the preview and the PDF          | + page-number furniture
                                   v                                       v
     pre-save:   [ PREVIEW - NOT FOR EXECUTION . agreementmitra.com ]   (URL + marker, no number yet)
                                   |
                              Save & continue -> createAgreement (returns trackingNumber) -> generate draft
                                   |
     post-save:  client shows AM-<LAST6>-<DDMMYY>; preview shows it (screen-only body line);
                 PDF footer stamps [ AM-... . agreementmitra.com    Page X of Y ] per page (furniture).
```

Provenance rendering is a **hybrid** (see design D3/D4 -- settled after a rendered PDF showed the body
line orphaning onto its own page and the page total rendering blank):
- **Preview (screen):** a **screen-only** body line at the document foot (`.doc-provenance` =
  `display:none`, shown under `@media screen`) -> shows the tracking number + URL in the HTML preview.
- **PDF (print):** the render sets `emulatedMediaType=print`, which **hides** that body line (no orphan,
  no overlap), and Gotenberg stamps the tracking number + URL + `Page X of Y` as **footer furniture** in
  the reserved bottom margin, **per page**. The footer uses a **table** layout so Chromium fills the
  `.totalPages` total (a flex row left it blank).
- **Parity:** `previewHtml` is byte-for-byte the HTML handed to the PDF renderer; only the render medium
  differs (screen vs print).

## 2. Root cause (why the preview shows nothing today)

The on-screen preview is `<iframe :srcdoc="previewHtml" sandbox="">` fed by the HTML variant of the
stateless preview -- a continuous document with no footer. The reference/page-number footer that exists
today is Chromium **furniture**, applied only when rendering a PDF and only on the saved-agreement tier.
So the preview a user reads carries no reference or platform mark, and the raw UUID never becomes a
readable, client-visible tracking number. Fix: move the reference + URL into the compiled body, keep
page numbers in furniture, and surface the number through `AgreementResponse` to the client.

## 3. The gap = what this CR does

| # | Item | Kind | Where |
| --- | --- | --- | --- |
| 1 | Provenance line (tracking number + URL) in the compiled body | **Engine** | `documents.template` (`TemplateCompiler`, `DocumentProjectionService`) |
| 2 | Tracking number `AM-<LAST6>-<DDMMYY>` (derived, not persisted) | **Engine** | `signing` (`AgreementDocumentService` helper) |
| 3 | Furniture reduced to page numbers only, every PDF page | **Engine** | `documents` (`GotenbergClient`) |
| 4 | Platform URL from app config (no brand literal in module) | **Config + engine** | `application.yml` + `DocumentFooterProperties` |
| 5 | `AgreementResponse.trackingNumber` exposed to the client | **Engine** | `signing.api` (response + mapper) |
| 6 | Frontend: show the number after save + pass it into the preview | **Frontend** | `CaptureForm.vue` + api client |

## 4. Contracts / boundaries (keep these)

- **Preview <-> PDF body parity:** the provenance line is compiled body -> the live-pane HTML stays
  byte-for-byte the PDF's HTML source. One compiler, one compile path, one set of resolved inputs.
- **Compiler stays pure + domain-agnostic:** the reference and URL are **resolved values passed into**
  `compile` (like the execution date); the compiler reads no config and the module holds no brand
  literal (URL is app config, default blank omits its part).
- **Markup/data boundary:** the provenance reference + URL are `HtmlUtils.htmlEscape`-d, like all body
  text.
- **Display-only tracking number:** last-4-hex is 24 bits -- **not** collision-free. The **full UUID
  stays the authoritative audit tie**; never key anything on the number. `trackingNumber` on the API is
  derived, not stored.
- **Reproducibility pin untouched:** the provenance line is system-owned compiler output (like the
  signature block), not template-content-hash input -- no pin/migration change.
- **Offline render:** the URL is inert display text (no anchor, no fetch); Gotenberg outbound stays
  denied. Never-log-HTML/PDF unchanged. Stateless preview still persists nothing, still `no-store`.

## 5. Coordination -- what this CR does NOT own

- **The pre-existing Chromium footer** (reference + page numbers) -> introduced by
  `agreement-execution-block` (active). This CR moves reference + URL to the body and reduces furniture
  to page numbers; whichever archives second reconciles the requirement.
- **A true persisted reference number** (`AM-2026-0001A7`, gap-free sequence) -> deferred follow-up
  (design D1) -- needs a column + allocation + migration.
- **Stamping, signing FSM, eSign/webhook** -> untouched.

## 6. Open decisions (resolved)

- **Preview shows the provenance** -- DECIDED: tracking number + URL live in the compiled body (preview
  + PDF); page numbers stay furniture (PDF only), because a continuous HTML preview has no pages.
- **Provenance line placement** -- DECIDED: once, at the document foot (per-page repetition survives only
  in the PDF page-number furniture).
- **Pre-save preview** -- DECIDED: shows the platform URL + a `PREVIEW - NOT FOR EXECUTION` marker (no
  number; no agreement exists yet).
- **Date source** -- DECIDED (D6): the agreement's **start date** ("Agreement date" on the doc),
  zero-padded `DDMMYY`. `NOT NULL`, always safe.
- **Client holds the number** -- DECIDED: `AgreementResponse.trackingNumber` (derived server-side,
  authoritative); the frontend does not re-derive it.

## 7. Kickoff checklist

1. Read `CLAUDE.md`, this CR's `proposal.md` + `design.md`, and `tasks.md`.
2. Infra up (compose Postgres/MinIO/Gotenberg; backend on `:8090`, local profile; frontend
   `npm run dev`). Windows: run backend tests via gradle directly with `TESTCONTAINERS_RYUK_DISABLED=true`
   and `-Duser.timezone=Asia/Kolkata`; write all files in pure ASCII.
3. Engine-first: task 1 compiler provenance line (+ resolved inputs); task 3 furniture -> page numbers;
   task 2 tracking-number derivation; task 5 response field; then task 6 frontend.
4. Ship unit + integration tests (backend) and a frontend unit test. Keep `ModularityTests` green; keep
   preview<->PDF body parity, the markup/data boundary, and the domain-agnostic module.
5. Live-verify: pre-save preview shows URL + PREVIEW marker; after Save the client shows
   `AM-<LAST6>-<DDMMYY>`, the preview body + PDF body show it, and the PDF stamps `Page X of Y` on
   every page.
