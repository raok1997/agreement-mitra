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

## What we are deliberately NOT doing yet

- No microservices, no message broker (add RabbitMQ/Kafka only when multi-step
  orchestration — KYC gate → e-stamp → multi-party signing — actually exists).
- No production ASP contract, no real Aadhaar data, no company/trademark work.
- No Drools wiring until the rules feature is specced.
