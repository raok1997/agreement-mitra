## Why

The signing vertical slice runs end-to-end against a **stubbed Leegality** provider and has
never touched a real eSign service. Leegality's Basic Plan is production-only and its
developer sandbox must be requested through support, which is why live testing has stayed
blocked (`docs/integrations/leegality.md`).

ZOOP's **eSign v5** API (`docs/integrations/zoop.md`) closes that. It supports two Aadhaar
signers in a single `/init` call, sequential ordering, ZOOP-delivered email invitations, a
multi-day signing window, and a real audit trail - and its test environment is free and
self-serve. Aadhaar eSign is Rs.10/signature against Leegality's Rs.25.

This change wires the customer journey end to end:

**build the agreement -> (payment) -> attach the e-stamp -> initiate Aadhaar eSign ->
track completion by webhook.**

Payment is included as a **gate that is currently open**. There is no payment gateway yet, and
picking one is a separate decision. But the ordering matters: staff spend real money buying an
SHCIL e-stamp, so once a gateway exists the gate must sit **before** stamp intake, not before
signing. Building the gate now - permissive, but real - means onboarding a gateway later is an
adapter plus a config flip, not a re-plumbing of the fulfilment pipeline.

## What Changes

- **New `payment-gate` capability.** An explicit, **configurable** precondition on the
  fulfilment pipeline. In `OPTIONAL` mode (the default, and the only mode usable today) the
  gate records payment state and allows progress regardless. In `REQUIRED` mode it blocks
  stamp intake and signing until payment is confirmed. No payment gateway, provider SDK, or
  card handling is introduced - only the gate and its state.
- **New `ZoopEsignProvider` adapter** behind the existing `EsignProvider` seam, targeting eSign
  v5: `POST /v5/init` with a `signers[]` array, `esign_type: AADHAAR`,
  `signing_type: SEQUENTIAL`, `send_invite: true`, and `txn_expiry_min`. The v5 `group_id`
  becomes our provider document id; each signer's `request_id` becomes the per-invitee
  correlation token.
- **BREAKING - `EsignProvider.verifyWebhook` changes shape.** ZOOP authenticates its callback
  with a `webhook-security-key` **HTTP header** whose value is issued **per transaction** at
  `/init`. The current contract is explicitly header-free and config-secret-based, so it
  cannot express this. Verification splits into parsing the untrusted transaction id from the
  body, then verifying the header value against that transaction's stored key.
- **Per-transaction webhook key is persisted**, encrypted at rest, and compared in constant
  time. It is a credential, not metadata.
- **eSign anchor -> coordinate mapping.** v5 places signatures by `page_num`/`x_coord`/`y_coord`.
  The adapter locates each `esign:<role>` anchor in the stamped PDF's text layer and converts
  it, **mirroring the x axis** - ZOOP measures `x_coord` from the **right** edge, PDF's native
  origin is bottom-left. `documents` learns nothing about ZOOP.
- **Artifact host pinning becomes an allowlist.** ZOOP's signed-document URLs are expiring
  links on a different host from the API.
- **Status visibility.** The customer can see per-party signing progress; staff can see it too.
- **Leegality is retained** behind the seam as the second adapter, selected by configuration.
  This change does not delete it.

Deliberately **not** in scope:

- Choosing or integrating a payment gateway; refunds; invoicing; pricing logic.
- The staff e-stamp flow - that is `manual-estamp-upload`, which this change depends on.
- ZOOP eStamp, templates, WhatsApp/Email/QuickSign eSign types, DSC, `location_capture` /
  `photo_capture`.

## Capabilities

### New Capabilities

- `payment-gate`: a configurable payment precondition on the fulfilment pipeline - payment
  state, the OPTIONAL/REQUIRED modes, where the gate is enforced, and the guarantee that
  enabling it later needs no pipeline redesign.

### Modified Capabilities

- `signing-request`: webhook authentication moves from a body `mac` (HMAC over the document
  id, config secret) to a per-transaction header key; the `EsignProvider` seam contract gains
  a header parameter and drops its Leegality-specific wording; authoritative status is read
  from the v5 fetch API; secrets/config cover the ZOOP credentials and the per-transaction
  key. Adds multi-signer sequential Aadhaar initiation, provider-delivered invitations, and
  signature placement by coordinates.

## Impact

**Code** - new `signing.zoop` adapter package (`ZoopEsignProvider`, config, properties,
anchor-to-coordinate mapper); `signing.EsignProvider` + `SignSession`/`DocumentStatusView`
(contract change); `signing.api.WebhookController` (header plumbing);
`signing.signingrequest.SigningRequestService` (payment gate check, webhook path); new payment
gate component; `signing.leegality` updated to the changed interface.

**Schema** - one forward-only migration: the per-transaction webhook key (encrypted) on
`signing_request`, and payment state. `ddl-auto: validate` stays green.

**API** - `POST /api/webhooks/esign` now reads the `webhook-security-key` header; new/extended
status endpoint; stamp intake and signing gain a `402`-or-`409` path when the gate is REQUIRED.

**Config** - `esign.provider` selector (`zoop` | `leegality`); ZOOP base URL, `app-id`,
`api-key` from env vars; `txn_expiry_min`; artifact host allowlist; `payment.mode`.

**Dependencies** - none new; PDFBox already present for text-position extraction.

**FSM** - no new states. `STAMPED -> SIGN_REQUESTED` gains the payment-gate precondition;
`SIGN_REQUESTED -> SIGNED | FAILED | EXPIRED` is driven by aggregating per-invitee statuses
exactly as today. The async webhook flow's **authentication** changes but its shape does not -
the sequence diagram is in `design.md`.

## PII / security review

- **Does this change introduce or move Aadhaar/OTP/VID/PII or secrets?** **Yes, both.**
  - **PII inbound (new).** The v5 webhook and fetch responses carry eKYC-derived signer data:
    `fetched_name` (the name ZOOP's ESP read from Aadhaar), `given_name`, email, city,
    `postal_code`, and `name_match_score`. This is richer signer PII than the Leegality
    payload. No Aadhaar number, VID, or OTP is received - authentication happens on the ESP's
    page, never through our API.
  - **Secrets (new).** ZOOP `app-id` / `api-key`, plus a **per-transaction**
    `webhook_security_key`.
- **How is it redacted/secured?** Credentials come from env vars only. The per-transaction key
  is stored **encrypted at rest**, never logged, and compared in **constant time**. Webhook and
  fetch payloads are never logged verbatim; `group_id`/`request_id` are redacted to a trailing
  fragment as the existing helper does. Signing URLs stay bearer capabilities and are never
  logged. Because the header key does not bind to the payload body, the webhook remains a
  **trigger only** - authoritative state is always re-read from the fetch API, so a forged body
  cannot drive an FSM transition. `location_capture` / `photo_capture` are deliberately left
  off to avoid widening the PII surface.
- **Sandbox + dummy data only preserved?** **Yes.** All work targets
  `https://test.zoop.plus/contract/esign` with dummy signers; tests run against WireMock and
  require no live credentials. The production host is configuration, never a default.
