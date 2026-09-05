# SUPERSEDED -- not implemented as a single change

This monolithic `agreement-document-render` proposal was **decomposed** into three small,
independently-shippable increments and retired here **unimplemented**. Its design decisions
(D1..D10) and PII/security checklist are reused **verbatim** across the increments; nothing here
was applied to the codebase.

Superseded by:

- **CR-3a `document-render-service`** -- the render capability only (Gotenberg service +
  `DocumentRenderer` impl + one bundled Thymeleaf `rental-agreement` template). Owns the
  `document-rendering` requirements: render-to-PDF, offline/network-deny, escaping.
- **CR-3b `agreement-preview`** -- agreement-to-template-data mapper + `GET /api/agreements/{id}/preview`
  (inline PDF, `no-store`, not stored) + the frontend Preview panel. Owns the preview +
  log-hygiene requirements.
- **CR-3c `agreement-generate-draft`** -- `POST /api/agreements/{id}/document` stores the render as
  the draft (overwrite-until-signing, then locked) + the frontend "use this document" action. Owns
  the `draft-ingestion` (system-generated draft) requirements.

Each increment carries its own `.flow-journal.md` and goes through propose -> review -> apply ->
validate -> manual-test -> archive on its own.
