# Tasks -- zoop-aadhaar-esign

Interface change first (S2), because it is breaking and both adapters must move together. Then
the ZOOP adapter (S3-S4), the webhook path (S5), the payment gate (S6), and status visibility
(S7). Behavioral change -> unit **and** integration tests (S9), per `dev-policy`. The
test-environment tracer (S8) is a **manual-test gate** and must pass before ZOOP is enabled
anywhere beyond the test host.

> **Depends on `manual-estamp-upload`.** Signing now requires an attached stamp, and the
> anchor-to-coordinate mapping runs over the **stamped** PDF (whose prepended certificate page
> shifts page numbers). Do not start S4 before that change lands.

> **Grounding note.** `EsignProvider`, `SignRequest`, `SignSession`, `DocumentStatusView`, and
> the FSM aggregation rule are already multi-invitee and vendor-neutral -- v5's `signers[]`
> maps onto them directly. The only structural change is webhook verification. Do not redesign
> the seam; extend it.

## 1. Configuration + schema

- [x] 1.1 New migration `V16__zoop_esign_payment.sql`. (Spec said `V15`; **V15 was already taken**
  by `V15__single_tracking_reference.sql`, so this landed as `V16`. No applied migration edited.)
- [x] 1.2 Add the encrypted per-transaction webhook key column to `signing_request` (nullable).
- [x] 1.3 Add payment state, amount, currency, external reference (unique where present),
  actor, and timestamp. All nullable so existing rows validate.
- [x] 1.4 Add `esign.provider` selector (`zoop` | `leegality`), ZOOP base URL / `app-id` /
  `api-key` from env vars, `txn_expiry_min`, artifact host allowlist, and `payment.mode`.
  Default provider host is the **test** host; production is never a default.
- [x] 1.5 Confirm the app boots with `ddl-auto: validate`.

## 2. EsignProvider interface change (breaking)

- [x] 2.1 Change webhook verification to receive request headers alongside the payload, and to
  support parse-then-verify (parse untrusted transaction id from body -> module loads that
  transaction's key -> adapter compares). Adapter gains **no** repository access.
- [x] 2.2 Update `LeegalityEsignProvider` to the new signature; its body-MAC path ignores the
  headers. Keep `LeegalityEsignProviderWireMockTest` green.
- [x] 2.3 Wire the provider selector so exactly one adapter is active per configuration.
- [x] 2.4 Replace single-host artifact pinning with the configured allowlist, in both adapters.

## 3. ZOOP adapter -- create + status + download

- [x] 3.1 New `signing.zoop` package: `ZoopEsignProvider`, config, properties. `app-id` /
  `api-key` headers. Nothing outside `signing` references it; keep `ModularityTests` green.
- [x] 3.2 `POST /v5/init`: map `SignRequest` invitees to `signers[]` with
  `esign_type: AADHAAR`, `signing_type: SEQUENTIAL`, `send_invite: true`, `txn_expiry_min`,
  `response_url`, `redirect_url`, and `task_id` = our agreement id. `document.info` must be at
  least 15 characters.
- [x] 3.3 Map the response: `group_id` -> provider document id; per-signer `request_id` ->
  per-invitee correlation token; `signing_url` -> invitee signing URL; `expires_at` -> expiry.
  Persist `webhook_security_key` **encrypted**.
- [x] 3.4 Check encoded document size against the 14 MB ceiling **before** the call; refuse
  with a clear error rather than sending.
- [x] 3.5 `GET /v5/fetch/group?group_id=` -> vendor-neutral per-invitee status view. Map
  transaction/signer statuses onto `SIGNED` / `REJECTED` / `EXPIRED` / `PENDING`, with unknown
  values falling back to `PENDING`.
- [x] 3.6 `download`: fetch `complete_signed_url` (host-allowlisted) plus the audit trail from
  `/v5/fetch/audit-trail`; return both with their declared content types.
- [x] 3.7 Redaction: never log `group_id`/`request_id` in full, signing URLs, the
  per-transaction key, or any payload. Never log eKYC fields (`fetched_name`, `given_name`,
  `postal_code`, `name_match_score`).
- [x] 3.8 Do **not** set `location_capture` or `photo_capture`.
- [x] 3.9 *(not in the original list, but required by the `signing-request` spec delta -- "A pending
  request can be extended and re-invited")*: `POST /v5/increase-expiry-time` and `POST
  /v5/send-esign-invitation` on the **same** `group_id`, exposed as STAFF-only
  `/api/staff/signing/{id}/extend` and `/resend`. Optional `EsignProvider` default methods, so
  Leegality (which offers neither) still compiles and refuses honestly. Tested: no second `/v5/init`
  is ever issued, so no second vendor charge is incurred.

## 4. Anchor-to-coordinate mapping

- [x] 4.1 PDFBox `PDFTextStripper` subclass locating each `esign:<role>` anchor in the
  **stamped** PDF, returning page number and position.
- [x] 4.2 Convert to v5 coordinates, **mirroring x** (`page_width - x`) for ZOOP's right-edge
  origin. Handle page rotation and non-A4 sizes.
- [x] 4.3 Refuse the request before the provider call if an expected anchor is missing -- never
  place at a default position.
- [x] 4.4 Keep the mapper inside the adapter package; no `documents` type crosses the boundary.

## 5. Webhook path

- [x] 5.1 `WebhookController` passes request headers through; still JSON-only with a bounded
  body size.
- [x] 5.2 ZOOP verification: parse `group_id` from the untrusted body, load that transaction's
  key, compare to the `webhook-security-key` header in **constant time**.
- [x] 5.3 Unknown transaction -> reject without disclosing that it is unknown. Verified webhook
  for an unknown id -> same acknowledgement as a known one (no existence oracle).
- [x] 5.4 Confirm the body is still **never** trusted: status always re-read via `getStatus`.
  This is now the primary control (design D2) -- add a comment saying so, so it is not
  "optimised" away later.
- [x] 5.5 Confirm the reconciliation job reuses the same completion path unchanged.

## 6. Payment gate

- [x] 6.1 Payment state (`UNPAID` / `PAID` / `WAIVED`), server-managed, not client-settable.
  `WAIVED` stays distinguishable from `PAID` everywhere.
- [x] 6.2 Gate modes `OPTIONAL` (default) and `REQUIRED`, switchable by configuration with no
  code or schema change; active mode observable at runtime.
- [x] 6.3 Enforce the gate at **stamp intake** and at **eSign initiation**, before any side
  effect -- no blob, no provider call, no transition, no vendor charge.
- [x] 6.4 Refusal is distinguishable from missing-draft / missing-stamp / uncontactable-party.
- [x] 6.5 Vendor-neutral payment-confirmation seam (agreement, amount, currency, external
  reference, time). STAFF-only manual confirmation and waiver are the only implementations.
- [x] 6.6 Unique external payment reference where present.

## 7. Status visibility

- [x] 7.1 Expose per-party signing progress plus the aggregate state.
- [x] 7.2 Ensure the view leaks no eKYC-derived PII, no other party's signing URL, and no
  provider credential; enforce ownership/staff authorization.

## 8. Test-environment tracer (MANUAL GATE -- do before enabling ZOOP further)

> **RUN 2026-09-11 -- five of six steps pass; 8.5 remains open.** This note previously read "NOT
> DONE, and cannot be done from here. No ZOOP account exists ... Nothing in this change has ever
> contacted a real ZOOP host". **That was wrong when written and stayed wrong for weeks**: the
> box geometry in `ZoopSignCoordinate` was calibrated against the live test host on 2026-08-28
> (viewer v4.2.0, a recorded group id), so a real call predated this note. The contradiction was
> found on 2026-09-11 while triaging this CR, and it had the practical effect of parking two CRs
> as "blocked on an account that does not exist". Status tracked in `docs/integrations/zoop.md`
> section 5.

- [x] 8.1 Sign up for free staging access; configure the test host and credentials via env.
  Credentials supplied from env against `https://test.zoop.plus/contract/esign/` (the shipped
  default; the production host is never a default).
- [x] 8.2 Run one real `/init` against `https://test.zoop.plus/contract/esign` with two dummy
  Aadhaar signers, `SEQUENTIAL`, `send_invite: true`, on a stamped fixture document.
  **Done 2026-09-11**: agreement `AM63AV7B8WZ`, Telangana residential, transaction
  `6aa386abc8889fad5a2e857a`, two invitees at signing order 0 and 1.
- [x] 8.3 **Visually confirm** both signatures land inside their intended signature zones. This
  is the acceptance check for S4 -- the mirrored x axis fails silently, so an assertion over our
  own arithmetic is not sufficient. **PASS -- not mirrored.** Full inspection (all seven
  placement checks) recorded under `esign-signature-placement` task 7.4; that CR's section 7 and
  this step were closed by one run, since this step is a subset of its inspection.
- [x] 8.4 Confirm both invitation emails arrive and the 7-day expiry is honoured. **PASS.** Both
  invitations arrived and both parties signed (10:13:20 and 10:15:03 IST). Expiry stored as
  `2026-09-18T04:42:19Z` against a request created `2026-09-11T04:42:19Z` -- exactly the
  configured 7 days (`txn-expiry-min: 10080`), confirmed from the persisted invitee rows rather
  than from the mail body.
- [ ] 8.5 Confirm the webhook arrives with the `webhook-security-key` header and that the
  transaction completes end to end to `SIGNED` with artifacts stored.
  **NOT VERIFIED -- the 2026-09-11 run did not close this.** ZOOP completed the signing and
  emailed the parties, but **no callback reached the app**: the run used a local host that ZOOP
  could not reach. The persisted state proves the gap rather than inferring it --
  `signing_request.status` is still `SIGN_REQUESTED`, both invitees still `PENDING`,
  `signed_pdf_key` and `audit_trail_key` both null. So the signed artifacts were never downloaded
  or stored, and nothing downstream of completion ran.
  - The per-transaction `webhook_security_key` **was** issued and persisted (encrypted), so the
    half of this step that precedes the callback is in place; what is unproven is receipt,
    header verification, and the completion path.
  - **A passing provider email is not evidence for this step.** "The document has been completely
    signed" comes from ZOOP, carries our `org-name`, and says nothing about our callback. Anyone
    re-running this must check the persisted state, not the inbox.
  - The scheduled reconciliation fallback (`signing.reconciliation`, 5-minute interval over
    requests older than 15 minutes) is designed to recover exactly this case and also did not
    advance it on this run -- worth checking deliberately when 8.5 is re-run, since a missed
    webhook is the scenario it exists for.
  - To close: re-run with a publicly reachable callback (`ZOOP_RESPONSE_URL` must be set **before**
    the app starts -- it is read at startup and baked into the `/init` call).
- [x] 8.6 Record the outcome in `docs/integrations/zoop.md`. **Done 2026-09-11**, section 5.

## 9. Tests

**Unit:**

- [x] 9.1 Init request mapping: two invitees -> one `signers[]` payload with `AADHAAR`,
  `SEQUENTIAL`, `send_invite`, expiry, `task_id`; `document.info` >= 15 chars.
- [x] 9.2 Response mapping: `group_id`, per-signer `request_id`/`signing_url`/order, `expires_at`.
- [x] 9.3 Status mapping incl. unknown vendor values -> `PENDING`; FSM aggregation unchanged.
- [x] 9.4 Anchor mapper: correct page and position per role; **x mirrored**; rotation and
  non-A4 handled; missing anchor refuses.
- [x] 9.5 Webhook verification: correct key passes; wrong key, missing header, and a key valid
  for a *different* transaction all rejected; comparison is constant-time.
- [x] 9.6 Oversized document refused before the call.
- [x] 9.7 Redaction: no payload, key, signing URL, or eKYC field reaches logs.
- [x] 9.8 Payment gate: `OPTIONAL` permits `UNPAID`; `REQUIRED` blocks `UNPAID` and admits
  `PAID`/`WAIVED`; refusal distinguishable.
- [x] 9.9 Artifact URL on an unlisted host is refused.

**Integration:**

- [x] 9.10 WireMock v5 happy path (mirroring `LeegalityEsignProviderWireMockTest`): init ->
  webhook -> fetch -> `SIGNED` -> artifacts in MinIO.
- [x] 9.11 Partial signing (owner only) -> no FSM transition, no error.
- [x] 9.12 Forged body with a valid key -> authoritative fetch wins, no wrong transition.
- [x] 9.13 Fetch failure after a verified webhook -> acknowledged, reconciliation completes it.
- [x] 9.14 Payment gate `REQUIRED`: stamp intake and eSign initiation both blocked for `UNPAID`,
  with no provider call and no vendor charge.
- [x] 9.15 Provider selector: switching to `leegality` keeps the existing suite green.
- [x] 9.16 Schema boots under `ddl-auto: validate`; `ModularityTests` green.

## 10. Docs + housekeeping

- [x] 10.1 Update `docs/ROADMAP.md`: Track B is no longer gated on the Leegality sandbox; ZOOP
  test access is free and self-serve.
- [x] 10.2 Record the tracer result and any vendor answers in `docs/integrations/zoop.md`
  (section 5 open questions).
- [x] 10.3 Document the ZOOP env vars in the README/local-run notes.
- [x] 10.4 Ran `./gradlew spotlessApply` then `./gradlew test spotbugsMain` (`osv-scanner` is not
  installed here, so `check` fails closed by design). Result: **679 tests, 0 failures, 0 errors,
  0 skipped**; SpotBugs/FindSecBugs: **0 findings**. (Final re-run after the extend/re-invite
  addition: **683 tests, 0 failures, 0 errors, 0 skipped**.)
