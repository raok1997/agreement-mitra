# Tasks -- staff-queue-fulfilment-context

Backend projection first (S1-S2), because the wire shape has to settle before the console can
render it. Then the API DTO (S3), then the frontend (S4). Behavioral change on both sides ->
unit **and** integration tests (S5), per `dev-policy`. No migration: every field already exists.

## 1. Projection types (signing.agreement)

- [x] 1.1 Add `StaffPartyView(Role role, String name, String fatherName)` as a public record in
      `in.agreementmitra.signing.agreement`, with javadoc naming it a STAFF-only fulfilment
      projection.
- [x] 1.2 Override `toString()` on `StaffPartyView` to emit role only -- never the name or
      father's name (mirrors `Signer.toString()`).
- [x] 1.3 Extend `StaffAgreementView` with `List<StaffPartyView> parties`, `String templateName`,
      `String templateState`; defensively copy the party list so the record is immutable.
- [x] 1.4 Override `toString()` on `StaffAgreementView` to emit agreement id + tracking reference
      + party COUNT only -- never a party name.
- [x] 1.5 Replace the "staff need to disambiguate, not to read the customer's agreement" javadoc
      rationale with the fulfilment rationale (design D5), stating the exclusion list explicitly:
      no contacts, no rent, no deposit, no full street address.

## 2. Projection assembly (AgreementService)

- [x] 2.1 In `toStaffView`, project the aggregate's signers to `StaffPartyView`s using
      `Signer.name()` and `Signer.fatherName()`, ordered owners-before-tenants with stored order
      preserved within each role (design D1).
- [x] 2.2 In `staffViewsByAgreementId`, collect the distinct non-null `templateId`s across the
      batch and resolve each once through the already-injected `TemplateCatalogApi` (design D2)
      -- no per-row lookup.
- [x] 2.3 Resolve through the NON-THROWING `TemplateCatalogApi.find` (added for this: `detail`
      throws inside the caller's transaction, marking it rollback-only and 500-ing the whole queue
      even when caught -- see revised design D2). Unresolved or null template -> null name and
      state, row STAYS on the queue.
- [x] 2.4 Take the row's state from `TemplateDetail.Dimensions.state()`, never from the property
      address (design D3). Leave the existing `propertyCity` derivation untouched.
- [x] 2.5 Confirm no log statement on the assembly path interpolates a view, a party, or a signer.

## 3. Wire DTO (signing.api)

- [x] 3.1 Extend `StampQueueEntry` with `templateName`, `templateState`, and the party list
      (role + name + fatherName per party); document it as STAFF-only.
- [x] 3.2 Map the new `StaffAgreementView` fields through `StampIntakeService.awaitingStampQueue`
      onto the entry, leaving ordering, exclusion, and closure filtering unchanged.
- [x] 3.3 Verify `SecurityConfig` still gates `/api/staff/**` with `hasRole(STAFF)` -- no
      authorization change, asserted rather than assumed.

## 4. Staff console (frontend)

- [x] 4.1 Widen the `StampQueueEntry` interface in `src/api/staffQueue.ts` with the template and
      party fields; note in its header comment that the row is no longer non-PII.
- [x] 4.2 Render template name + state on the row headline in `StaffConsole.vue`, with the state
      as its own badge (design D6) -- it selects which stamp to buy.
- [x] 4.3 Render the parties beneath the headline as the name with `Father: <name>` under it,
      grouped under "First party" / "Second party" labels, all parties of each role. Neutral
      label, not "S/o" -- we store no gender or relationship (design D6).
- [x] 4.4 Render absent template metadata as an explicit "Template unavailable" note rather than
      an empty gap.

## 5. Tests

- [x] 5.1 **Unit** -- `StaffPartyView.toString()` and `StaffAgreementView.toString()` do NOT
      contain a party name or father's name they were constructed with (design D4).
- [x] 5.2 **Unit** -- `toStaffView` orders owners before tenants and preserves stored order within
      a role; an agreement with two owners and one tenant projects all three parties.
- [x] 5.3 **Unit** -- a null `templateId` and an unresolvable one both yield null template name +
      state without throwing.
- [x] 5.4 **Unit** -- the projection carries no email, mobile, rent, deposit, or full street
      address (assert on the record's components, so adding a field later fails this test).
- [x] 5.5 **Unit (frontend, vitest)** -- `StaffConsole.vue` renders template, state, and every
      party with its father's name; a row with absent template metadata renders the unavailable
      note and still shows its Upload control.
- [x] 5.6 **Integration** -- `GET /api/staff/estamp/queue` as STAFF returns rows carrying template
      name, template state, and all parties with father's names, longest-waiting first.
- [x] 5.7 **Integration** -- an agreement pinned to a non-PUBLISHED template still appears on the
      queue, with template name and state null.
- [x] 5.8 **Integration** -- a non-STAFF caller is refused and the response body carries no party
      name and no queue size.
- [x] 5.9 **Unit** -- template resolution runs once per distinct template across a multi-row queue,
      not once per row (asserted on the port interaction count, which is mockable only at the unit
      level; written as "integration" when planned).
- [ ] 5.10 Keep `ModularityTests` green -- `signing` still names no `documents.template` type.

## 6. Verification

- [x] 6.1 `./gradlew test` (or `./run-tests.sh`) green; `./gradlew spotlessApply` clean. Run in
      class batches (signing.agreement.*, StampIntakeServiceTest, StampQueueFulfilmentIntegrationTest,
      ModularityTests, PaymentGateIntegrationTest, SignedDeliveryIntegrationTest): 96 + 24 tests, 0
      failures.
- [x] 6.2 `npm run lint` and `npx vitest run` green in `frontend/`: 144 tests pass, eslint clean on
      the touched files, `vue-tsc` clean, prettier clean.
- [ ] 6.3 Manual: sign in as STAFF, open `/staff`, confirm a real row shows template, state, and
      both parties with father's names, and that the stamp upload still attaches from the row.
