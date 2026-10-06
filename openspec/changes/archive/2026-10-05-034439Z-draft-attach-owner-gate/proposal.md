## Why

Once an agreement is claimed, its read (`GET /{id}`), edit (`PUT /{id}`) and contacts (`PATCH /{id}/contacts`) routes answer only its owner. But generate (`POST /{id}/document`), draft upload (`POST /{id}/draft`), preview (`GET /{id}/preview`) and finalise (`POST /{id}/finalise`) still check no owner. So anyone holding the link can still do three things:

- replace the PDF the parties will sign;
- download a rendered PDF carrying every party's details;
- place the order, which freezes the owner's terms and puts the agreement in the staff stamp queue.

This must close before real users arrive. It is the High-priority `draft-attach-owner-gate` row in the follow-up register, widened to cover `finalise`, which has the same gap.

## What Changes

- **Owner gate on four routes.** Generate, draft upload, id-bound preview and finalise apply the rule the contacts route already uses:
  - an **unclaimed** agreement answers anyone presenting its id;
  - a **claimed** one answers only its owner;
  - anyone else gets the same `404` as an unknown id, so ownership cannot be probed.
  - The check runs **before** any other check (draft validation, the draft freeze, closure, the draft-present check, the jurisdiction gate). Otherwise a non-owner could tell a claimed agreement from an unknown one by getting a `400`/`409` instead of a `404`.
  - The filter chain keeps these routes `permitAll`. The check is in the handler, because only the handler can see the row's owner, exactly as for `GET /{id}`.
- **One copy of the owner rule.** "Unowned, or owned by the caller" is written out three times in `AgreementService` today (`findByIdForReader`, `isAccessibleBy`, `updateContacts`), and this change would add four more. It moves onto the `Agreement` aggregate as one method (`admits(caller)`), and every caller uses it.
- **One "not available" message in the capture form.** When a call on the capture form's pay path is refused `404`, the form shows one message, points to signing in, and re-checks the session. The calls are: loading the agreement for the contact step, saving contacts, finalising, and the checkout that follows. The message hedges the same way for an unknown agreement and for one claimed by another account. A customer whose session ended meets the `404` at the first of these calls; today it reads "Could not load the party details. Please try again." The UI never shows who claimed an agreement.
- **The status view says the same.** Its "Complete payment" (stamp-quote load, then checkout) shows the same message and re-checks the session on a `404`, in place of "Contact support quoting your reference". Folded in at manual test.
- **The generate request's template pin is gated too.** It is written in its own transaction after the draft is stored, and loads under the row lock, so a claim landing in between can neither be written over nor undone.
- **The "TEMPORARY – tighten when ownership lands" comments** on these routes in `SecurityConfig` and `AgreementController` are resolved.

Not a breaking change for any legitimate flow. The capture form calls generate before it claims, and every call after the claim carries the owner's session.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-security-baseline`: **ADDED** a requirement that the drafting surface (generate, draft upload, id-bound preview, finalise) is owner-scoped once an agreement is claimed, with no ownership oracle and the owner check ordered before every other refusal. It is ADDED rather than MODIFIED on `draft-ingestion` / `agreement-preview` because the active change `byo-document-upload` already MODIFIES those two requirements, and two MODIFIED deltas on one requirement overwrite each other at archive.
- `client-error-reporting`: **MODIFIED** "A refusal whose remedy differs is explained in its own words" to add the not-available refusal (a `404` on any call of the capture form's pay path (contact-step load, contacts save, finalise, checkout), or on the status view's payment) and its message.

## Impact

- **Backend (signing module only):**
  - `Agreement` gains the owner-rule method.
  - `AgreementService` delegates to it at its three existing call sites.
  - Each of these takes the caller's identity and gates on it: `DraftService.attachDraft`, `AgreementDocumentService.renderPreview` / `renderForDraft`, and `SigningRequestService.finalise`.
  - `AgreementController` passes `@AuthenticationPrincipal` to all four handlers.
  - The `SecurityConfig` comments change; its matchers do not.
  - No migration, no new dependency, no module-boundary change.
- **Signing flow:** `finalise` creates the signing request in `PDF_GENERATED`. This change only adds an authorization check **before** that, and no FSM transition is touched (`PDF_GENERATED → STAMPED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED` is unchanged). The async eSign path, webhook and reconciliation are untouched.
- **Frontend:** `src/api/problems.ts` (not-found type), `src/views/refusalMessages.ts` (the new message), three `CaptureForm.vue` catches on the pay path, and the two payment catches in `AgreementStatus.vue`. The dead `fetchAgreementPreview` export (no caller) is removed. There is no frontend caller of the id-bound preview or of draft upload, so gating them has no visible UI effect.
- **Sibling change `byo-document-upload`** (proposed, not implemented) rebases onto this one. Its own ownership task is satisfied here, its preview branch must use the gated load, and its "owner or link holder" wording means link holders only while unclaimed (design D5).
- **Tests:** existing integration tests that claim an agreement and then call these routes without a session will start getting `404`, and are updated to send the owner's session.
- **PII / security review:**
  - **No new outbound flow** of Aadhaar, OTP, VID or PII, and no secret is introduced or moved.
  - **The change narrows PII exposure.** A claimed agreement's rendered PDF, which holds every party's details, stops being downloadable by a non-owner.
  - **Nothing new is logged.** Not-found messages keep using the redacted agreement id.
  - Sandbox and dummy data only, unchanged.
- **Register:** closes the `draft-attach-owner-gate` row. It appends to `claim-bound-to-initiator`: how ownership is *acquired* (first claim wins on a bearer id) is now the remaining weak point, and this change makes the first claimant's control wider.
