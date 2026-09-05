# Tasks -- post-payment-continuity

> **The reference is not the credential.** The tracking reference selects an agreement; the
> emailed link authorises access to it. No endpoint added here may return agreement data in
> response to a reference alone. If a task seems to require it, the task is wrong.

> **Uniform responses are load-bearing.** The recovery endpoint must answer identically for
> "reference does not exist", "not paid", "already claimed", "no contact on file", "throttled",
> and success. A different status, body, or error path in any of those cases reintroduces the
> enumeration oracle the design exists to prevent.

> **There is no token.** The link carries the agreement identifier and IS the access (D2). Do
> not add a token table, a redemption endpoint, an expiry sweep, or a scoped principal type. If
> an implementation starts growing one, stop and re-read D2 and D4 -- the permanence is
> deliberate, and claiming is the revocation.

> **One definition of contactable.** The channel rule from S1 is the only reachability rule in
> the system when this lands. `SigningRequestService.requireContacts` (email OR mobile) is
> replaced by it, not left alongside it. Two rules that disagree is the defect this fixes.

Channels and the reachability rule first (S1), because everything else is expressed against
them. Then the pre-checkout step and its server gate (S2), lookup and recovery mail (S3-S4),
the send-on-payment path that does the actual work (S5), the recovery endpoint and its abuse
controls (S6), then the remaining frontend surfaces (S7-S8). Behavioural change -> unit **and**
integration tests (S9), per `dev-policy`. S10 is documentation.

## 1. Delivery channels and the reachability rule

- [x] 1.1 Model delivery channels -- email, SMS, WhatsApp -- with per-channel enablement in
  configuration. Email enabled; SMS and WhatsApp declared and **disabled**.
- [x] 1.2 A common interface for dispatching through a channel, shaped in the same spirit as
  `EsignProvider`. Build it to what email actually needs; do not invent SMS/WhatsApp semantics
  from guesses.
- [x] 1.3 Email adapter over the existing `SmtpEmailSender` / `MailConfig`. Add no second mail
  path.
- [x] 1.4 A single reachability rule: a party is reachable when they have a valid contact on at
  least one **enabled** channel. Expressed once, in one place, and used by every caller.
- [x] 1.5 A contact on a disabled channel never satisfies reachability, whatever is stored.
- [x] 1.6 Replace `SigningRequestService.requireContacts` with a call to 1.4. Update the tests
  that assert the old email-or-mobile behaviour; expect the eSign gate to reject cases it used
  to admit -- that is the correction, not a regression.

## 2. Pre-checkout contact confirmation

- [x] 2.1 Server precondition on order creation: refuse unless every party is reachable per
  1.4. This is the enforcement; the UI step is not.
- [x] 2.2 The refusal names which parties are unreachable and what is needed, without leaking
  anything beyond the agreement the caller already holds.
- [x] 2.3 Contact details are NOT accepted in the order-creation body -- they are agreement
  data, corrected through the agreement.
- [x] 2.4 Frontend step between finalise and checkout: every party, the details held, and what
  each is for. Correction in place.
- [x] 2.5 When every party is already reachable the step reads as a confirmation, not a form --
  no re-entry of details already held.
- [x] 2.6 Do not present a disabled channel as a delivery route. Mobile is described as used
  for signing notifications and future delivery, never as "we will SMS you" while SMS is off.
- [x] 2.7 Present the step as a distinct screen in the capture flow, entered on "finalise and pay"
  and left only by confirming or going back. Not a separate route: the step needs the agreement
  the flow is already holding, and `App.vue`'s path switch carries no state between routes.
- [x] 2.8 `PATCH /api/agreements/{id}/contacts` (D16): contacts only, unowned agreements only,
  refused once a signing request exists. Ignores every other agreement field.
- [x] 2.9 `SecurityConfig`: the contacts route permitted anonymously, scoped to the exact
  sub-path, owner-checked in the handler like the rest of the customer surface.
- [x] 2.10 Send the current draft agreement to every party after contacts are confirmed and
  before checkout (D17). A distinct message from the recovery link, carrying the agreement.
- [x] 2.11 A send failure does not block payment: log, record, and let checkout proceed.
- [x] 2.12 Keep the recovery message free of agreement content -- the two messages must not
  converge. Assert it in a test.

## 3. Eligibility and lookup

- [x] 3.1 A reference-to-agreement lookup usable outside the STAFF role, kept **separate** from
  `AgreementService.findByTrackingReference` so the staff projection (`StaffAgreementView`) is
  not widened to anonymous callers.
- [x] 3.2 Normalise and checksum-validate the reference before any lookup. Malformed input is
  rejected as malformed -- this is not an existence signal, and must not consume rate budget
  differently from a well-formed miss.
- [x] 3.3 Eligibility: payment state `PAID` or `WAIVED`, **and** `owner_identity_id` is null.
  Both conditions, evaluated server-side only.
- [x] 3.4 The retired `AM-<LAST6>-<DDMMYY>` form stays refused, via the existing
  `isLegacyDerivedFormat` path.

## 4. Recovery mail

- [x] 4.1 Recovery message template: tracking reference, link, and the revocation notice ("this
  link works until you sign in and save this agreement"). No party names, no property address,
  no rent or deposit. Assert this in a test, not just in review.
- [x] 4.2 Recipients resolved **server-side** as **every party** with a contact on an enabled
  channel, dispatched through the S1 channel interface. Not just the payer. No destination is
  accepted from any request body, and the recipients are never returned to a caller.
- [x] 4.2a Per-recipient dispatch is independent: one party's delivery failing must not stop
  another party's. Record the outcome per recipient.
- [x] 4.3 Link construction from a configured public base URL -- not from a request header, so
  a spoofed `Host` cannot rewrite where the customer is sent.
- [x] 4.4 Fail closed: with no enabled channel configured, or no contact on file, send nothing
  while any caller-facing response is unchanged.
- [x] 4.5 Redact the recipient in every log line and audit record.

## 5. Send on payment confirmation

- [x] 5.1 On payment confirmed by the server, send the recovery link. Hook the existing
  confirmation path so it fires once per agreement regardless of which producer confirmed --
  browser callback, webhook, or reconciliation.
- [x] 5.2 Idempotent: repeated confirmations of the same agreement do not send repeated mail.
- [x] 5.3 Dispatch failure must not fail the payment confirmation. Log, audit, and continue --
  the customer's money has already moved, and the confirmation view still shows the reference.

## 6. Recovery request endpoint

- [x] 6.1 `POST /api/agreements/recovery` -- accepts a reference, returns `202` with an empty
  body in **every** case. No agreement data, no existence signal.
- [x] 6.2 `SecurityConfig`: the route permitted anonymously, scoped to the exact sub-path (not
  a wildcard), consistent with the existing customer-surface comments.
- [x] 6.3 Rate limiting per source and per reference, with lockout. Throttled responses must be
  shape-identical to unthrottled ones.
- [x] 6.4 Payload size limit applied, matching the existing registration in
  `PayloadSizeLimitConfig`.
- [x] 6.5 Flyway migration for the recovery audit table -- forward-only, next free version at
  implementation time (V18 was the last applied when this was written). Reference, outcome,
  timestamp, redacted recipient, requester fingerprint.
- [x] 6.6 Audit record written for every request, including refusals and throttled attempts.
- [x] 6.7 App boots under `ddl-auto: validate` with the new table.
  - Verified 2026-09-05: `V19__recovery_audit.sql` is the head migration, `application.yml` pins
    `ddl-auto: validate`, and the live local stack answers `200` on `:8090/actuator/health` -- the
    real app booting, not only the test profile (which is also `validate`, 911 tests green).
- [x] 6.8 Confirm `ModularityTests` stays green -- no reach into `identity` internals.
  - Verified 2026-09-05: `TEST-in.agreementmitra.ModularityTests.xml` present and green in the
    `./run-tests.sh` run (0 failures / 0 errors across 911 tests, 0 skipped).

## 7. Payment confirmation view (frontend)

- [x] 7.1 New confirmation view showing the tracking reference prominently, the
  **server-confirmed** amount and currency, and the next step.
- [x] 7.2 `CaptureForm.vue` hands off to it on confirmed payment, replacing the inline banner.
- [x] 7.3 The view renders only from server-confirmed payment state. It must not claim success
  from the checkout handler's return value -- preserve the existing guarantee in
  `api/payments.ts`.
- [x] 7.4 State that a link has been sent, and that signing in to save the agreement ends
  access by link.
- [x] 7.5 Where no contact is on file, say so plainly and tell the customer to keep the
  reference, rather than implying something was sent.
- [x] 7.6 Wire the view into the path-based switch in `App.vue` (no vue-router).

## 8. Recovery request page and link landing (frontend)

- [x] 8.1 Recovery request page: enter a reference, submit, and receive the same "check your
  email" outcome in all cases.
- [x] 8.2 Client-side checksum validation for typos, before submission, so a mistyped reference
  is corrected rather than silently mailed nowhere.
- [x] 8.3 Link landing route: read the agreement identifier from the link and resume through
  the existing endpoints.
- [x] 8.4 A link for an agreement that has since been claimed produces a clear, non-leaking
  message ("this agreement is saved to an account -- sign in to open it") and a route to login.
- [x] 8.5 `Referrer-Policy` on the landing route so the identifier is not disclosed
  cross-origin; exclude the identifier from any analytics or error-reporting payload.
- [x] 8.6 Entry point to the recovery page from the landing page.

## 9. Tests

**Unit**

- [x] 9.1 Channel enablement: an enabled channel satisfies reachability; a disabled one never
  does, whatever contact is stored.
- [x] 9.2 Reachability rule across the party matrix -- some parties reachable, none, all -- and
  that it is the same rule the eSign gate now calls.
- [x] 9.3 Reference normalisation, checksum acceptance and rejection, legacy-form refusal.
  - Verified 2026-09-05 by existing coverage, no new test in the diff: `TrackingReferenceTest`
    (`normalizeMakesLowercaseAndPaddedInputTheSameReference`, `normalizeIsNullSafeAnd...`,
    `aSingleMistypedCharacterIsRejected...`, `transposingTwoAdjacentCharactersIsRejected`,
    `theRetiredDerivedFormatIsRecognisedAndIsNeverValid`) covers all three clauses.
- [x] 9.4 Eligibility matrix: PAID/WAIVED/UNPAID x owned/unowned -- only paid-and-unowned is
  eligible.
  - Closed 2026-09-05: `RecoveryIntegrationTest.onlyASettledUnownedAgreementIsRecoverable` walks all
    five rows.
  - **The task wording above is narrower than the implemented rule, and the code is right.**
    `AgreementService.findRecoverableByTrackingReference` accepts `PAID` **or** `WAIVED`, both
    unowned. A waiver is the deliberate decision to proceed without money, so a waived customer has
    the same claim on reaching their agreement. The test asserts the WAIVED row explicitly so it
    cannot be "tidied away" by someone reading only this sentence.
- [x] 9.5 Recipient resolution returns **every** party contactable on an enabled channel, not
  only the payer, and ignores any supplied destination.
- [x] 9.5a One recipient's dispatch failure does not suppress the others.
- [x] 9.6 Message body assertion: contains reference, link, and revocation notice; contains no
  party name, property address, rent, or deposit.
- [x] 9.7 Link construction uses the configured base URL and ignores request headers.
- [x] 9.8 Redaction: recipient appears redacted in every log and audit path.
- [x] 9.9 Send-on-confirmation is idempotent across repeated confirmations.
  - Closed 2026-09-05: `RazorpayPaymentIntegrationTest.aRedeliveredWebhookDoesNotMailTheCustomerTwice`
    -- three identical signed webhook deliveries, one mail per party. The guarantee comes from
    `PaymentConfirmations.apply` taking the order `FOR UPDATE` and returning `ALREADY_CONFIRMED`
    **before** the publish; moving the publish above that guard would mail once per provider retry.
- [x] 9.10 Frontend: the contact step renders as confirmation when nothing is missing, and as a
  form when something is; disabled channels are not described as delivery routes.
- [x] 9.11 Frontend: payment confirmation view renders server-confirmed values and does not
  render on handler-only success; recovery page validates the checksum client-side.

**Integration**

- [x] 9.12 Order creation is refused when any party is unreachable, and the refusal names them.
- [x] 9.13 Order creation succeeds when every party is reachable on an enabled channel.
- [x] 9.14 A party reachable only on a **disabled** channel is refused at order creation --
  the case that passes the old email-or-mobile gate today.
- [x] 9.15 Order creation ignores contact details supplied in its own request body.
  - Closed 2026-09-05: `ContactGateIntegrationTest.orderCreationIgnoresContactDetailsSuppliedInItsOwnBody`
    -- a body carrying `contacts` and `signers` for an unreachable party is still refused for that
    same party, and the smuggled address is asserted absent from the stored signers.
- [x] 9.15a Contacts save for the agreement's OWN owner (authenticated), not only for an
      anonymous caller -- the signed-in customer reaches the same contacts screen, and a
      refusal there also cost them the draft that is sent immediately afterwards.
- [x] 9.15b Contacts on an agreement owned by somebody else answer 404 identically whether
      the caller is anonymous or authenticated as a different identity (no ownership oracle).

- [x] 9.16 eSign initiation now applies the same rule, rejecting a party it previously admitted.
  - Closed 2026-09-05: `ContactGateIntegrationTest.esignInitiationRefusesAPartyTheOldEmailOrMobileRuleWouldHaveAdmitted`
    -- a mobile-only party (admitted by the retired email-OR-mobile rule) is refused `409
    contact-required` at `POST /api/signing/*/request`, and no signing_request row is written.
  - Asserted as exactly `409`, not "some refusal": the route is `permitAll`, so a looser assertion
    would have passed on an auth rejection and proved nothing about reachability.
  - **Observed, not a defect:** unlike the checkout gate, this one does not name the offending party
    -- the body is the generic "Every party needs a contact we can reach them on before payment",
    which also says "before payment" on a signing path. Worth tidying; deliberately not asserted.
- [x] 9.17 The recovery endpoint returns an identical response across all outcomes -- unknown
  reference, unpaid, claimed, no contact on file, throttled, success. Assert body and status
  equality explicitly, as one test, so a future change cannot regress one branch quietly.
- [x] 9.18 End-to-end: pay an agreement, capture the message sent at confirmation, open the
  link, and reach the agreement through the existing endpoints.
  - Closed 2026-09-05: `RazorpayPaymentIntegrationTest.payingSendsEveryPartyALinkTheyCanOpen` --
    anonymous checkout, signed webhook settles, the captured message carries the base URL and the
    agreement id, and that link opens the agreement `200`.
- [x] 9.19 End-to-end fallback: request recovery by reference, capture the message, open the
  link, reach the agreement.
- [x] 9.20 A link continues to work after an arbitrary delay while the agreement stays paid and
  unowned.
  - Closed 2026-09-05: `aLinkKeepsWorkingWhileTheAgreementStaysPaidAndUnowned` ages both
    `created_at` and `payment_recorded_at` by 400 days and reopens -- `200`. Guards D2's "no
    per-link state, therefore no expiry" against someone later adding an implicit window.
- [x] 9.21 Claiming the agreement makes a previously working link refuse access -- including a
  link held by a party other than the one who claimed.
- [ ] 9.21a A party who did not pay can open their link and complete the remaining fulfilment
  steps.
- [x] 9.21b Every party on a paid agreement receives a link at payment confirmation.
  - Closed 2026-09-05: same test -- both owner and tenant receive exactly one message each.
- [x] 9.22 A recovered agreement remains unowned and can still be claimed afterwards.
  - Closed 2026-09-05: `aRecoveredAgreementStaysUnownedAndCanStillBeClaimedAfterwards` -- recovery
    re-sends a link without taking ownership, and claiming afterwards still revokes the link.
- [x] 9.23 Terms and parties cannot be edited by a caller holding only the link.
  - Closed 2026-09-05: `aLinkHolderCannotEditTermsOrParties` -- read with the link succeeds, `PUT`
    is refused, and the stored address is asserted unchanged so a silently-ignored write cannot pass.
- [x] 9.24 Rate limit and lockout behaviour, including shape-identical throttled responses.
- [x] 9.25 With no enabled channel configured, nothing is dispatched and responses are
  unchanged.
  - Closed 2026-09-05: new `RecoveryWithNoEnabledChannelIntegrationTest` (its own class -- channel
    enablement is context configuration, not per-test state). Asserts nothing is dispatched, the
    eligible and unknown responses stay byte-identical, and the request is still audited.
  - Why it matters beyond hygiene: a deployment with no channel configured must not become the one
    case where the endpoint answers differently, or a misconfiguration turns into an enumeration
    oracle.
- [x] 9.26 A dispatch failure at payment confirmation does not fail the confirmation.
  - Closed 2026-09-05: `RazorpayPaymentIntegrationTest.aDispatchFailureDoesNotUnsettleTheConfirmedPayment`
    -- with the mail seam failing everything, the webhook is still acknowledged, the agreement is
    still `PAID`, and it is still reachable. Asserts the outcome rather than trusting that
    `RecoveryOnPaymentListener` swallows.
- [x] 9.27 `ModularityTests` green.
  - Verified 2026-09-05 -- same run as 6.8.

## 10. Documentation

- [x] 10.1 `README.md`: the recovery flow, its env requirements (public base URL, channel
  enablement), and that it is inert without an enabled channel.
- [x] 10.2 Record in `docs/ARCHITECTURE.md` that the tracking reference remains
  non-authorising, and that recovery works by delivering the agreement identifier out of band
  -- so a later reader does not "simplify" this into a reference lookup.
- [x] 10.3 Document that claiming is the revocation mechanism, so it is not removed as
  incidental.
- [x] 10.4 Document the channel model and that SMS/WhatsApp are declared but disabled, so the
  next person knows where an adapter goes.
- [ ] 10.5 Resolve or explicitly defer Q1a-Q4 from `design.md` before archiving.
