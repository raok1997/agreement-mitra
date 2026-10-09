## Why

What the customer pays to stamp and sign is the **saved** agreement, but nothing on the way to
payment shows them its terms. Before the 2026-10-09 lock, an edit after saving changed the preview
but not the record that gets paid for (save at ₹25,000, edit to ₹24,000, sign a ₹25,000 deed). The
lock closes that path, but the saved record is still never put in front of the customer at the point
of payment. This change does that, so a mismatch is caught at the last step whatever the editing rules
become. It is change (1) of the `after-save-editing` register row, which comes before the planned
"editable until payment" change (2) because it stays useful after (2) lands.

## What Changes

- The stamp-quote step (the screen with the stamp duty and **Continue to payment**) opens with a
  **"You are paying to stamp and sign this"** summary. It shows the property address, monthly rent,
  security deposit, start and end dates with the term, and the landlord and tenant names.
- The summary is read from the **saved** agreement on the server (`GET /api/agreements/{id}`), never
  from the on-screen form or the browser draft.
- A one-line "Something wrong? Go back before you pay" prompt points to the existing **Back** action.
  From there the screen behind the step already offers the right fix: **Edit agreement** for a
  signed-in owner, or **Start a new agreement** for an anonymous one.
- **Continue to payment** stays disabled until the summary has loaded. If the saved terms cannot be
  read, the step shows an error and cannot be paid from.
- ToS §7 ("You are shown the stamp, the duty and the total before you pay") also promises that the
  customer is shown the agreement's main terms as saved.
- Not in scope: changing what can be edited after save (the lock stays; that is change (2)), a
  confirmation checkbox or recorded assent (the separate ToS-acceptance checkpoint item), and any new
  backend endpoint.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `stamp-selection`: adds a requirement that the saved key terms are shown with the stamp quote
  before payment, read from the stored agreement, and that payment cannot start until they are shown.

## Impact

- **Frontend:**
  - `frontend/src/views/StampQuoteStep.vue` gets the summary block and a `getAgreement` fetch next to
    `getStampQuote`.
  - Its two parents, `CaptureForm.vue` and `AgreementStatus.vue`, change only to import the extracted helpers.
  - Tests that mount the step (`StampQuoteStep*.test.ts`, `CaptureForm*.test.ts`,
    `AgreementStatus.test.ts`) must mock `getAgreement`.
- **Content:** `frontend/src/content/termsOfService.ts` §7 gets a one-sentence change.
- **Shared helpers:** `roleLabel` and the "agreement unavailable" error mapping move to shared
  modules, so the step does not copy them (design D5/D6).
- **Backend:** none. The existing `GET /api/agreements/{id}` already serves an unclaimed agreement to
  anyone holding its id, and a claimed one to its owner.
- **Not covered here:** binding the shown terms to the order. Today only a signed-in owner's second
  tab can change the terms between the summary and payment. That binding is recorded against
  change (2), which widens editing (design Non-Goals).
- **Signing FSM:** no transition touched. This sits entirely before payment and before
  `PDF_GENERATED`.
- **PII / security review:**
  - The summary shows the property address, the terms and party names and roles. The reader set is
    unchanged: anyone holding the id of an unclaimed agreement, or the owner of a claimed one, can
    already read the same record through the same endpoint, and the capture flow already fetches it
    on this path. No new data leaves the server.
  - The step keeps only the shown fields in memory. No party email, mobile, address or father's name
    and no captured values are stored or shown.
  - No Aadhaar, OTP, VID or secrets are involved, and nothing is logged.
  - Sandbox and dummy data only, as before.
