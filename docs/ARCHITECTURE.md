# AgreementMitra — Architecture

This is the durable record of the architecture decisions. CLAUDE.md is the
short version Claude loads every session; this is the detail. Treat changes to
these decisions as proposals (via OpenSpec), not casual edits.

## Product

Online rental-agreement platform for India. The MVP capability is Aadhaar
eSign (OTP) of rental agreements. Planned later: KYC + DigiLocker with
fraud/forgery detection; a multi-state legal-logic rules engine that
auto-generates jurisdiction-correct agreements; vernacular/multilingual
document generation.

## Stack decision and rationale

- **Backend: Java 21 + Spring Boot 3.x.** Chosen over PHP/Laravel because this
  is a production system (not a throwaway prototype) owned by a Java expert,
  and because the flagship future feature — the multi-state rules engine — is
  a near-perfect fit for Drools (JVM-native, mature). Greenfield, so no
  migration cost.
- **Modular monolith (Spring Modulith).** One deployable with enforced internal
  module boundaries. Gives clean seams to extract services later *if* scale
  demands, without paying the microservices tax now.
- **Frontend: Vue 3 + Vite + TypeScript + Tailwind.** Uses the framework the
  team already knows (not React). Responsiveness is handled by Tailwind;
  interactivity needs are light (trigger + live status).
- **PostgreSQL** for relational state and the audit trail. **Object storage**
  (MinIO local, S3-compatible prod) for signed/unsigned PDFs.

## Modules

| Module      | Responsibility                                               | Status   |
|-------------|--------------------------------------------------------------|----------|
| `signing`   | Agreements, signing requests, status FSM, webhook intake, `EsignProvider` + vendor adapters | active   |
| `documents` | Template → PDF (headless Chromium / Playwright), fonts        | active   |
| `identity`  | Aadhaar/PAN/DigiLocker KYC, fraud/forgery checks             | future   |
| `rules`     | Multi-state legal-logic rules engine (Drools)               | future   |

Modules expose a small public API package; everything else is package-private.
Cross-module communication is via published interfaces or Spring application
events — never direct access to another module's internals. `ModularityTests`
enforces this.

## Signing flow (asynchronous)

```
Vue SPA ──create agreement──▶ signing
signing ──render──▶ documents (template → PDF)
signing ──create sign request──▶ EsignProvider (Leegality sandbox)
        ◀── signing URL ──
signer authenticates (Aadhaar + OTP) on the ESP page
EsignProvider ──webhook: signed PDF + audit──▶ signing.WebhookController
signing updates status ──SSE/poll──▶ Vue SPA
```

The request thread never blocks on a signature. Webhook drives completion; a
scheduled reconciliation job polls for any signing requests stuck past a
timeout (covers missed webhooks).

## eSign vendor strategy

`EsignProvider` is the abstraction. Start on **Leegality** (license-free
sandbox, built-in e-stamping which rental agreements need, document templating,
webhooks). Keep **Digio** in mind as the likely production swap when KYC /
DigiLocker arrive, since it bundles KYC + eSign + eStamp behind one API. The
abstraction means switching is one adapter, not a rewrite.

## Document generation

`documents` renders a template to PDF via headless Chromium (Playwright for
Java). Pure-Java PDF libraries do not shape complex Indic scripts correctly, so
Chromium + bundled Noto fonts (Devanagari, Tamil, etc.) is the path that
future-proofs the vernacular feature. English-only would be simpler today, but
we build the Chromium pipeline from the start to avoid a later rearchitecture.

## Access to an agreement: what authorises what

Three values can identify an agreement, and only one of them authorises anything.
Conflating them is the most likely way to introduce a serious PII leak here, so
the distinction is recorded rather than left to be rediscovered.

- **The agreement UUID is a bearer capability.** 122 bits, unguessable. Any caller
  presenting it may read an **unowned** agreement; once claimed, only the owner
  may. That is the existing model and it predates recovery.
- **The tracking reference authorises nothing.** `AM` + eight characters + a check
  character - roughly 40 bits. It appears on the rendered document, in emails, and
  in support conversations. It is a *selector*, never a credential.
- **An identity session** is what a claimed agreement requires.

**Recovery works by delivering the UUID out of band, not by looking up the
reference.** Entering a reference causes a link to be emailed to the parties
already on the agreement; it never returns agreement data, and it answers
identically whether or not the reference matched. This is what lets a 40-bit
value be usable in a support conversation without making every paid agreement
enumerable.

A future change that "simplifies" this into a reference lookup that returns the
agreement would reverse it. So would adding a "not found" response to the
recovery endpoint. Both leak full signer PII - names, fathers' names, addresses,
rent - to anyone who guesses a code.

**Claiming is the revocation.** The emailed link does not expire. It stops working
when the agreement acquires an owner, because anonymous access to a claimed
agreement is already refused. That behaviour is load-bearing now, not incidental:
it is the only off-switch a permanent link has, and the customer is told about it.

**One definition of "contactable".** A party is reachable when they have a contact
on an **enabled delivery channel**. That single rule gates order creation and eSign
initiation. It replaced an earlier email-or-mobile check that disagreed with
email-only delivery, letting a mobile-only party sign an agreement they could never
be sent. Two rules that disagree is the defect; keep it one.

## What we email a customer, and when

Three distinct messages reach the parties, at three different moments. They are easy to conflate --
two of them carry links that look similar and only one is per-party -- so the distinctions are
recorded here rather than rediscovered.

| # | Message | Trigger | Per party? | Carries |
|---|---|---|---|---|
| 1 | **The draft** | saving contacts (`PATCH /api/agreements/{id}/contacts`) | No | the draft document |
| 2 | **The recovery link** | payment confirming | No -- one URL for everyone | a link only, no attachment |
| 3 | **Signing invitations** | staff attaching the stamp, which starts signing | **Yes** -- one `sign_url` per signer | that signer's own signing URL |

Three things people get wrong about this:

- **The draft is not sent by paying.** Its only trigger is a contacts save. Contacts must be set to
  pass the checkout gate and freeze once paid, so in practice it lands just before payment and feels
  like a payment email -- but correcting contacts twice sends it twice, and paying sends it never.
- **Only the signing invitation is per-party.** The recovery link is `<base>/agreement/<uuid>`,
  identical for every party. `SigningRequestInvitee` is what holds a per-signer `sign_url`.
- **The recovery message deliberately carries no attachment** (asserted by
  `RecoveryDeliveryServiceTest.theMessageNeverCarriesAnAttachment`). It is a way back in, not a copy
  of the document -- the copy is message 1.

**Between payment and message 3, the customer has nothing to do.** Staff attach the stamp; the
customer waits. That dead interval is what the `agreement-status-link-page` change addresses -- today
the recovery link lands them on a capture form they cannot submit, because the agreement froze at
finalise.

**Who paid is never an authorisation input.** In practice whoever starts the agreement pays, but the
system records no payer-only rule and must not gain one (design D15 in `post-payment-continuity`):
any party holding the link is equally entitled. Guarded by
`RazorpayPaymentIntegrationTest.payingSendsEveryPartyALinkTheyCanOpen`.

## What we are deliberately NOT doing yet

- No microservices, no message broker (add RabbitMQ/Kafka only when multi-step
  orchestration — KYC gate → e-stamp → multi-party signing — actually exists).
- No production ASP contract, no real Aadhaar data, no company/trademark work.
- No Drools wiring until the rules feature is specced.
