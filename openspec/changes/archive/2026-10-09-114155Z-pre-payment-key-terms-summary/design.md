## Context

`StampQuoteStep.vue` is the last screen before money moves. It loads `getStampQuote(agreementId)` on
mount and shows the duty, the breakdown, the registration notice and the stamp options, then
**Continue to payment · ₹X**. Its template is one chain keyed on the quote: `loading`, then
`loadError` (`:175-179`), then unavailable, then the quote body. Continue sits in the quote body
(`v-if="quote?.available"`, `:330`) and is disabled by `canPay` (`:76`). Back is unconditional
(`:322-329`). The quote payload deliberately carries no party names or address (`stamp-selection`,
"The customer is shown the stamp quote before paying"). So today the step shows nothing about *what*
is being paid for.

Two parents render it:
- `CaptureForm.vue:1104`, in the capture flow after the contact step. Back (`cancel`) sets
  `stampStep = false` and returns to the locked form. The form's `locked-notice` (`:1489-1528`)
  already offers **Edit agreement** (claimed), **Save it to your account** (signed in, unclaimed) or
  **Start a new agreement** (anonymous).
- `AgreementStatus.vue:515`, the "Complete payment" path from the emailed link. It is shown only while
  no order exists. The page already has a `status-terms` block, and its role labels come from a local
  `roleLabel` (`:95-96`: Owner / Tenant / Party).

`GET /api/agreements/{id}` (`getAgreement` → `AgreementView`) is readable by **anyone holding the id**
while the agreement is unclaimed (a bearer capability, `Agreement.admits`, `Agreement.java:351-358`),
and only by its owner once claimed. Any other caller gets a 404, the same as for an unknown id. That
is the same caller set as the stamp quote (both are `CAPABILITY_READ` in `RouteClassifier`). The
capture flow already calls it on this path (`CaptureForm.openContactStep`, `:876`) and maps a 404
through a local `unavailable(e)` (`:902`: problem type not-found, plus `reconcile()` of the session)
to `AGREEMENT_UNAVAILABLE_MESSAGE` (`refusalMessages.ts:32`). Rent and deposit come back in rupees
with up to two decimals (`@Digits(fraction = 2)`), while quote amounts are in paise.

## Goals / Non-Goals

**Goals:**
- Show the stored key terms next to the price, before the customer can pay.
- Make the summary impossible to source from client-held state.
- Change nothing on the backend.

**Non-Goals:**
- **Binding the shown terms to the order.** The summary is the record as stored *when the step
  opens*. Nothing carries a version from the summary to `finaliseAgreement`. Today only a signed-in
  owner editing the same agreement in a second tab can open that window, because the capture form is
  locked after save. The binding belongs with change (2) (editable until payment), which widens
  editing and is what makes the window matter. It is appended to the `after-save-editing` row at
  archive.
- Changing what may be edited after save. The lock stays.
- Recording assent (a checkbox, a stored acceptance). That is the separate ToS-acceptance checkpoint.
- Showing contact details. Those are confirmed on the contact step just before.

## Decisions

**D1: The summary lives inside `StampQuoteStep`, not in a parent.** Both payment paths render this
step, so both get the summary with no parent changes. Putting it in `CaptureForm` alone would leave
the AgreementStatus "Complete payment" path without it. *Alternative rejected:* a separate
`KeyTermsSummary` component composed by each parent. That gives two wiring sites, and the next
payment path to be added could forget it. Because D1 is justified by both paths, both are tested
(tasks 3.2 and 3.3).

**D2: The step fetches `getAgreement(agreementId)` itself on every mount, independently of the
quote.** Each load has its own `try/catch` and its own state; a failure in one never masks the other.
This uses two separate calls, not one shared `try` and not `Promise.all`. The step never takes an
agreement through props and never caches one: the parent's copy, a store or a shared cache are exactly
the client-held state this change guards against. Back followed by re-entry remounts the step, which
refetches; that is intended, so do not optimise it away. `agreementId` is read once in `onMounted`.
Neither parent changes it while the step is mounted, because both gate the step with `v-if`. A
response arriving after unmount writes to a dead ref, which is harmless, so no request cancellation is
needed.

**D3: The response is narrowed at fetch time.** The step stores only
`{ propertyAddress, monthlyRent, securityDeposit, startDate, endDate, durationMonths, parties: [{ name, role }] }`.
It does not keep the `AgreementView`. Email, mobile, `currentAddress`, `fatherName` and `captureData`
never enter component state, so a later template edit cannot leak them and devtools on a shared
device does not show them. A response missing any of these fields is treated as a load failure.

**D4: Render precedence.** The summary section renders independently, above the quote chain:

| Terms state | Summary section shows | Stamp options | Continue to payment |
|---|---|---|---|
| pending | "Loading the saved terms…" | per quote state | present if quote available, **disabled** |
| loaded | the summary | per quote state | per quote state; `canPay` also requires terms loaded |
| error | `role="alert"` message | **hidden** | **hidden** |

The quote chain is unchanged: its own loading, error and unavailable messages still render below the
summary. When both loads fail, both messages show, the summary error first (an identical message only once, see D5). Back is present in every
row.

**D5: Error copy reuses the capture flow's mapping, extracted rather than copied.** CaptureForm's local
`unavailable(e)` moves to a shared helper beside `AGREEMENT_UNAVAILABLE_MESSAGE` in
`refusalMessages.ts`, and both callers use it. A copy would put the same rule in two places. The
terms error is:
- `AGREEMENT_UNAVAILABLE_MESSAGE` for a not-found refusal (the session is re-checked, so a stale
  signed-in header is corrected);
- otherwise `busyMessage(e)` for busy or rate-limited responses;
- otherwise "Could not load the saved terms. Please try again."

It never shows `error.message` or a status code. `agreementUnavailable` also counts the stamp
quote's status-only 404 (`StampQuoteHttpError`), so AgreementStatus's local
`notAvailableToThisSession` copy goes and the quote's own load error reads the same; when both
reads give that message the step shows it once.

**D6: Formatting.**
- Rent and deposit use `formatRupees` (`agreementListFormat.ts:18`), which shows paise when the
  stored amount has any (it previously rounded them away; AgreementStatus's own copy of that
  formatter is replaced by it too).
- Dates use `formatIso` (`dateEntry.ts:80`); an empty result renders "—".
- The term is the server's `durationMonths`, never derived from the dates on the client, rendered
  "1 month" or "N months".
- Party roles come from **one** `roleLabel` helper, extracted from `AgreementStatus.vue:95-96` into
  `agreementListFormat.ts`: Owner / Tenant / Party for null or unknown. Both views use it, so the
  AgreementStatus page does not label the same person two ways. The capture flow's contact step also
  says "Owner".
- Every value uses `{{ }}` interpolation and never `v-html`. The address uses `whitespace-pre-line` to
  keep the line breaks the customer typed.

**D7: Placement and copy.** A bordered `<section data-testid="key-terms">` sits directly under the
"Stamp duty" heading, headed "You are paying to stamp and sign this". It holds a `<dl>` with Property,
Monthly rent, Security deposit, Term ("01/11/2026 to 31/10/2027 · 12 months") and Parties
("Ravi Kumar (Owner)"), then "If anything here is wrong, don't pay. Go back." The copy names no
destination, because Back from the AgreementStatus path lands on a status page, not the form. Tailwind
utilities only, single column on phones.

## Risks / Trade-offs

- **Shown terms are not bound to paid terms.** See Non-Goals. The residual window today is a signed-in
  owner's second tab. It moves to the `after-save-editing` row as a requirement change (2) inherits:
  finalise refuses when the terms changed since they were shown, for example via a terms fingerprint.
- **AgreementStatus shows the terms twice** (its `status-terms` block and the new summary). This is
  accepted. The summary is the "this is what you are paying for" frame next to the price, and hiding it
  there needs the prop that D2 rejects.
- **On the AgreementStatus path, Back offers no fix.** The page has no edit path, so an anonymous
  customer who spots an error must start a new agreement. That is the gap change (2) closes, and it is
  appended to the same row.
- **ToS versioning.** §7 changes, so `lastUpdated` moves and `docs/TERMS-OF-SERVICE.md` is re-rendered.
  §7 is `drafted`, so no counsel re-review is triggered, but the date keeps "the version published when
  you paid" truthful.
