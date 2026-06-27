## Why

The signing module is the heart of AgreementMitra, but it has no domain to sign:
every type is a stub and there is no persisted agreement. Before we can create a
signing request (CR-3) we need the thing being signed — a rental agreement that
knows **who** must sign it. Real Indian rental agreements have **multiple owners
and multiple tenants**, each a distinct person who signs via their own Aadhaar+OTP.
This CR establishes that aggregate as the foundation the rest of the signing flow
builds on.

## What Changes

- Introduce an `Agreement` aggregate (persisted, status-less) in the signing
  module's internal package, carrying the rental terms (property address, monthly
  rent, security deposit, term in months) and a collection of **signers**.
- Model each **signer** as an addressable child entity — `{name, email,
  role: OWNER | TENANT}` — with its own server-assigned id, so an agreement can
  have N owners and N tenants. (CR-3 will hang per-signer provider session + state
  off this row; modelling it as an entity now avoids a later remodel.)
- Add `POST /api/agreements` (create) and `GET /api/agreements/{id}` (read) to the
  signing module's public HTTP surface, with bean-validated request/response
  records (anti-mass-assignment: ids and `createdAt` are server-assigned).
- Enforce strict create-time validation: 1–20 signers, valid email per signer, at
  least one OWNER **and** at least one TENANT, no duplicate emails within one
  agreement (case-insensitive), plus rental-term bounds (non-blank address, positive
  rent, non-negative deposit, two-decimal money, positive term).
- Add a Flyway migration (`V2__agreement.sql`) creating the `agreement` and
  `signer` tables; JPA stays `ddl-auto: validate` (Flyway remains the single
  source of truth).
- Permit exactly `POST /api/agreements` and `GET /api/agreements/*` in the existing
  root `SecurityConfig` — **method-and-path-scoped, not a `/**` wildcard** — so future
  sub-paths stay denied by default (sandbox-bounded, TEMPORARY — tighten when an auth
  mechanism lands), matching the existing permit pattern for `/api/signing/*/request`.

This CR is deliberately **status-less and vendor-free**: no `SignatureStatus` field,
no eSign/webhook/Leegality logic. Those land in CR-3/CR-4. The existing single-signer
`SignRequest` stub is left untouched (it widens to a signer set in CR-3). The deferral
introduces **no half-open signing path** in CR-2: there is no provider call, no webhook,
and nothing acts on untrusted external input beyond the validated JSON body — the
existing webhook permit's own deferred-HMAC caveat is untouched here.

A separate small follow-up CR (`validation-error-responses`) is proposed to add a safe
field-level validation-error contract (global `ProblemDetail` advice); CR-2 accepts
opaque 400 bodies for now (the platform is configured not to echo binding errors, and no
frontend consumes them yet).

## Capabilities

### New Capabilities
- `agreement-management`: create and retrieve a multi-party rental agreement —
  the persisted aggregate (rental terms + a collection of owner/tenant signers),
  its create-time validation rules, and the create/read HTTP endpoints. The
  foundation the signing request (CR-3) and document rendering (CR-5) build on.

### Modified Capabilities
<!-- None. No existing capability's requirements change. The signing FSM, webhook
     intake, and eSign provider behaviour are unspecified today and are introduced
     by later CRs (CR-3/CR-4), not modified here. -->

## Impact

- **New code** (signing module, internal `in.agreementmitra.signing.agreement`
  package): `Agreement` + `Signer` JPA entities, `Role` enum, a Spring Data
  repository, a package-internal application service, and the create-time
  validation constraint.
- **New code** (signing module public API, `in.agreementmitra.signing.api`):
  `AgreementController` plus `CreateAgreementRequest` / `AgreementResponse` (and
  nested signer) records, alongside the existing `SigningController`.
- **Schema**: new `backend/.../db/migration/V2__agreement.sql` (forward-only;
  never edits V1). Tables `agreement` and `signer` (signer carries an
  `agreement_id` FK + index).
- **Security**: one added `permitAll` matcher for `/api/agreements/**` in the
  root `SecurityConfig`.
- **Module boundaries**: all new internals stay package-private / Modulith-internal;
  only the `api` package surface is public. `ModularityTests` must stay green.
- **No** changes to: the signing FSM, `EsignProvider`/`LeegalityEsignProvider`,
  webhook intake, `SignRequest`, object storage, or any other module.

## PII / security review checklist

- **Introduces or moves Aadhaar/OTP/VID/PII or secrets?** Signer **name + email**
  are stored — ordinary contact PII, not Aadhaar/OTP/VID. No Aadhaar number, OTP,
  virtual ID, biometric, or government identifier is collected, stored, or logged
  by this change. No secrets are introduced.
- **How redacted/secured?** No new logging of signer fields is added; nothing
  echoes the request body to logs. Email is persisted only to pass to the eSign
  provider later (CR-3). Standard validation rejects malformed input at the boundary.
- **Sandbox + dummy data only?** Preserved — this is local/sandbox schema and API
  work with dummy data; no live provider, no real PII, no production credentials.
- **Signing-status FSM transitions touched?** None. This CR is status-less; the
  FSM (`DRAFT → PDF_GENERATED → SIGN_REQUESTED → SIGNED | FAILED | EXPIRED`) is
  introduced in CR-3, not here.
- **Async signing / webhook flow touched?** None — no sequence diagram needed.
