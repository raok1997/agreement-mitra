# SUPERSEDED / DO NOT IMPLEMENT (2026-07-12)

> **This change is retired before implementation.** It was drafted (module "M5") before we discovered
> the CR-2 window had already planned the reproducibility pin. It **collided**: both this CR and
> `agreement-template-pin` proposed `V9` adding the same `template_content_hash` +
> `template_layer_versions` columns.
>
> **Resolution:**
> - **Version pinning is owned by [`agreement-template-pin`](../agreement-template-pin/proposal.md)**
>   (the CR-2 window's planned CR — `V9__agreement_template_pin.sql`). Do the pin there, not here.
> - **The attributes store is deferred, not cancelled.** It is genuinely needed only when a user must
>   *set* template-specific fields per agreement (the CR-3d "custom conditions" value). Today those
>   fields (`furnished`, `registrationResponsibility`, `lockInMonths`, …) render from the effective
>   template's **declared defaults** in the projection service — there is nothing to persist yet, so
>   the store buys nothing now. Re-propose it fresh (attributes-only, no pinning, its own migration
>   **after** V9) when custom-conditions work begins. The concept is preserved in
>   `document-templating-platform/exploration.md` and `flow-journal.md` (§3 M5).
>
> The stale design/tasks/spec files below described the merged attributes+pinning approach and are
> **no longer authoritative** — they remain only for history. Delete this folder when convenient.
