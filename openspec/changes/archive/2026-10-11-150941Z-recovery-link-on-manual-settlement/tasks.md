`RZP` below is `backend/src/test/java/in/agreementmitra/signing/payment/RazorpayPaymentIntegrationTest.java`. It is the home for the integration tests: it already has a staff session, `staffConfirms`, `claimFor`, the `RecordingEmailSender` and a public base URL. It has no waive helper: add `staffWaives` beside `staffConfirms`, modelled on `signing/AgreementIdLogRedactionIntegrationTest.java:198-206`.

## 1. Event and publisher

- [x] 1.1 Add `signing/agreement/PaymentSettledEvent.java`: a public record holding the agreement id, with a `toString()` that redacts it through `AgreementIds.redact`. Its javadoc states the contract from design D3: published once per agreement, on the first transition out of `UNPAID`, and not for every payment
- [x] 1.2 Inject `ApplicationEventPublisher` into `AgreementService`. Add one private helper that reads the prior payment state and publishes `PaymentSettledEvent` when it was `UNPAID`; call it from `recordPayment`, `recordGatewayPayment` and `waivePayment`, after the agreement lookup has succeeded (design D1, D2)
- [x] 1.3 Add `AgreementService.findRecoverableById`, sharing one "settled and unowned" predicate with `findRecoverableByTrackingReference`: extract only the two filters, not the reference validation (design D6)
- [x] 1.4 Remove the publish and the injected publisher from `PaymentConfirmations`; rewrite the class javadoc and both inline comments that refer to the event (the surplus branch and the "Published here and nowhere else" block) (design D4)
- [x] 1.5 `RecoveryOnPaymentListener`: listen for `PaymentSettledEvent`; rename `onPaymentConfirmed` to `onPaymentSettled`; look the agreement up with `findRecoverableById`; the WARN carries `AgreementIds.redact(event.agreementId())`, reads "after payment was settled", and logs no throwable and no exception message; update the javadoc to name the staff paths (design D6, D7)
- [x] 1.6 `RecoveryDeliveryService`: the per-recipient failure WARN gains `AgreementIds.redact(agreement.id())`, still with no throwable; the javadoc says its callers are payment settlement and the recovery endpoint (design D7)
- [x] 1.7 Delete `signing/payment/PaymentConfirmedEvent.java`

## 2. Unit tests

- [x] 2.1 `AgreementServiceTest`: add `@Mock ApplicationEventPublisher` (the class uses `@InjectMocks`, so without it every existing settle-path test fails on a null publisher)
- [x] 2.2 `AgreementServiceTest`: `recordPayment` on an `UNPAID` agreement publishes one `PaymentSettledEvent`
- [x] 2.3 `AgreementServiceTest`: `waivePayment` on an `UNPAID` agreement publishes one event
- [x] 2.4 `AgreementServiceTest`: `recordGatewayPayment` on an `UNPAID` agreement publishes one event
- [x] 2.5 `AgreementServiceTest`: `recordPayment` on a `PAID` agreement stores the new reference and publishes nothing
- [x] 2.6 `AgreementServiceTest`: `recordPayment` and `recordGatewayPayment` on a `WAIVED` agreement record the payment and publish nothing; the gateway call still returns `RECORDED`
- [x] 2.7 `AgreementServiceTest`: `recordGatewayPayment` returning `ALREADY_RECORDED` or `SURPLUS` publishes nothing; `waivePayment` on a `PAID` agreement publishes nothing
- [x] 2.8 `AgreementServiceTest`: `findRecoverableById` is empty for an `UNPAID` agreement and for an owned one, and present for a settled unowned one
- [x] 2.9 `PaymentConfirmationsTest`: remove the publisher from the constructor call and from the five `verifyNoInteractions` lines; the parameterised test over `RECORDED` and `ALREADY_RECORDED` loses its publish assertion and is renamed for what it still proves (order marked paid, not surplus); the surplus test drops "AndPublishesNothing" from its name
- [x] 2.10 `AgreementIdToStringRedactionTest`: assert `PaymentSettledEvent` in place of `PaymentConfirmedEvent`; the event is now in the test's own package, so the import goes, and `paymentConfirmedEvent()` is renamed
- [x] 2.11 `RecoveryDeliveryServiceTest`: when a send throws, the WARN contains the redacted agreement id and neither the full id nor the recipient's address
- [x] 2.12 A unit test for `RecoveryOnPaymentListener`: it sends for a recoverable agreement, sends nothing when `findRecoverableById` is empty, and swallows a lookup failure with a WARN that carries the redacted id

## 3. Integration tests (in `RZP`, over HTTP)

- [x] 3.1 Staff `POST /api/staff/payments/{id}/confirm` on an `UNPAID` agreement sends one recovery mail to each party's address on file
- [x] 3.2 Staff `POST /api/staff/payments/{id}/waive` on an `UNPAID` agreement sends one recovery mail to each party
- [x] 3.3 A second staff confirm with a corrected reference stores the reference and sends no second mail
- [x] 3.4 Rewrite the waiver-then-gateway test (near `RZP:661`): waive through `POST /waive` instead of `Payments.waive(jdbc, ...)`, then confirm through the gateway; the agreement is `PAID` and each party received exactly one mail across both steps
- [x] 3.5 Rewrite the staff-confirm-then-gateway test (`RZP:638-658`) and its javadoc: assert one mail per party after the staff confirm, and none after the gateway confirms the same payment id
- [x] 3.6 A staff confirm under a reference held by another agreement returns 409, leaves the agreement `UNPAID` and sends no mail. Call `mail.reset()` after the first, successful confirm: both agreements share the same party addresses (setup precedent `RZP:815-818`)
- [x] 3.7 An `UNPAID` agreement claimed with `claimFor` receives no mail when staff waive it, and none when the gateway captures its payment (two cases; the gateway one is where existing behaviour changes)
- [x] 3.8 Confirm the existing tests still hold without edits: the gateway happy path sends one mail per party (`RZP:677-689`), and a surplus payment sends none (`RZP:531-548`, `RZP:607-623`). Update the stale comments at `RZP:101`, the section header near `RZP:1067` and the javadoc at `RZP:1100-1105`

## 4. Confirmation screen

- [x] 4.1 `PaymentConfirmation.vue`: add the "saved to your account" state, which replaces the link paragraph with "This agreement is saved to your account. Sign in to open it at any time." The reference stays visible (design D8)
- [x] 4.2 `CaptureForm.vue`: pass that state when `claimed || editMode` (design D8). It takes precedence over `recoveryLinkSent`
- [x] 4.3 `PaymentConfirmation.test.ts`: the saved-to-account state shows the sign-in wording and not "We have emailed you a link", including when no email address is on file; the two existing states are unchanged
- [x] 4.4 `CaptureForm` tests: a signed-in customer whose agreement was claimed on save, and one who reopened an owned agreement in edit mode, each reach the confirmation screen in the saved-to-account state

## 5. Docs

- [x] 5.1 `README.md`: in the payment escape hatches, waiving or recording a payment by hand emails the recovery link to the parties; in the Recovery paragraph (near line 169), the link is emailed when payment is settled, and not for an agreement saved to an account
- [x] 5.2 `docs/DEPLOYMENT.md`, "A captured payment that was refused or not recorded", step 2: recording by hand emails the recovery link, and the later gateway delivery sends no second one
- [x] 5.3 `docs/ARCHITECTURE.md`: the outbound-message table's trigger for the recovery link becomes "payment being settled (gateway, staff confirm or waiver)"; the staff-alert notes say a manual confirmation or waiver raises no staff alert but does send the link, and a gateway payment after a waiver sends no second link
- [x] 5.4 Terms of service, both copies (`docs/TERMS-OF-SERVICE.md:110` and `frontend/src/content/termsOfService.ts:121`): the sentence "you are given a reference and, if you gave us an email address, a link back to it" gains "unless the agreement is saved to your account, which you open by signing in". Follow the terms revision convention already in those files
- [x] 5.5 Delete `PARKED.md` from this change

## 6. Gates

- [x] 6.1 `./run-tests.sh check` from `backend/` is green (includes `ModularityTests` and `securityScan`); report the wall-clock time. Task 3.5 must be done first: its test fails against the new behaviour until rewritten
- [x] 6.2 `npm run build` and `npm run lint` from `frontend/` are green
- [x] 6.3 `openspec validate --strict --type change --no-interactive recovery-link-on-manual-settlement` passes

## Coverage

| Capability | Scenario | Disposition | Where |
|---|---|---|---|
| agreement-recovery | Payment confirmation sends the link unprompted | COVERED | 2.4, 3.8 |
| agreement-recovery | A customer who never visits the recovery page is still covered | GROUPED | "Payment confirmation sends the link unprompted", 3.8 |
| agreement-recovery | A staff-recorded payment sends the link | COVERED | 2.2, 3.1 |
| agreement-recovery | A waiver sends the link | COVERED | 2.3, 3.2 |
| agreement-recovery | Correcting a reference on a settled agreement sends nothing further | COVERED | 2.5, 3.3 |
| agreement-recovery | A payment after a waiver is recorded without a second link | COVERED | 2.6 (gateway and staff), 3.4 (gateway) |
| agreement-recovery | A surplus payment sends nothing | COVERED | 2.7, 3.8 |
| agreement-recovery | A gateway confirmation of a payment staff already recorded sends nothing further | COVERED | 2.7, 3.5 |
| agreement-recovery | Settling a claimed agreement sends no link | COVERED | 2.8, 2.12, 3.7 (waive and gateway) |
| agreement-recovery | A refused staff confirmation sends nothing | COVERED | 3.6 |
| agreement-recovery | The confirmation screen does not promise a link for an agreement saved to an account | COVERED | 4.3, 4.4 |
| payment-processing | Staff recorded the same gateway payment by hand | COVERED | 3.5 |
| payment-processing | A surplus payment sends no recovery link | COVERED | 3.8 |
| payment-processing | The other scenarios of the surplus requirement (text unchanged by this delta) | GROUPED | their existing tests in `RZP`, `PaymentConfirmationsTest` and `AgreementServiceTest`, run by 6.1 |
| backend-security-baseline | Named toString output redacts the id | COVERED | 2.10 |
| backend-security-baseline | A full lifecycle at DEBUG leaks no agreement id | COVERED | `AgreementIdLogRedactionIntegrationTest`, run by 6.1; its staff waiver now also sends the recovery mail, so it exercises the new path |
| backend-security-baseline | The other six scenarios of the redaction requirement (text unchanged by this delta) | GROUPED | their existing tests, run by 6.1 |
