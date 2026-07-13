# SUPERSEDED -- not implemented (2026-07-12)

This `agreement-attributes-and-pinning` change (module "M5") was **retired before implementation** and
archived here unimplemented. It was drafted before we discovered the CR-2 window had already planned the
reproducibility pin, and it **collided**: both this CR and `agreement-template-pin` proposed `V9` adding
the same `template_content_hash` + `template_layer_versions` columns. No M5 code landed (no `V9`, no
attribute/pin fields on `Agreement`).

Resolution (the scope decomposed):

- **Reproducibility pin (record)** -- OWNED by **`agreement-template-pin`** (CR-3):
  `V9__agreement_template_pin.sql` adds only `template_content_hash` + `template_layer_versions`
  (nullable), `Agreement.pinEffectiveTemplate(...)`, and the generate-as-draft pin call. Never re-adds
  `template_id` (owned by `V8__template_catalog`). Applied there, not here.
- **Attributes store** (`attributes JSONB`), the **schema-driven dynamic mapper** (walk the pinned
  `FormSchema` declared fields, read core column OR attribute store per `field.type`), and
  **byte-stable reproduce-from-pin** (later renders resolve the *pinned* hash via a catalog
  resolve-by-hash seam, never "current") -- **deferred, not cancelled**. They are genuinely needed only
  when a user must *set* template-specific fields per agreement (the "custom conditions" value); today
  those fields (`furnished`, `registrationResponsibility`, `lockInMonths`, ...) render from the
  effective template's **declared defaults** in the projection service, so the store buys nothing yet.
  Re-propose fresh (attributes-only, its own migration **after** `V9`) when custom-conditions work
  begins. The concept is preserved in `document-templating-platform/exploration.md` (and its
  flow-journal, §3 M5).

The stale `design.md` / `tasks.md` / `specs/` here described the merged attributes+pinning approach and
are **no longer authoritative**; they remain only for history.
