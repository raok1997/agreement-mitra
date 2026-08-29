# ZOOP - eSign integration notes

Vendor facts for planning eSign work. **Decision: we are using ZOOP Aadhaar eSign.**

Sources: the ZOOP eSign **v5** Postman collection supplied by their support team
(2026-08-03), plus the Quagga Tech business proposal dated 2026-07-21 (Private &
Confidential; same handling as the Leegality pricing in `leegality.md`).

Vendor entity: **Quagga Tech Private Limited**, Pune (CIN U74999PN2016PTC158857).
The proposal is addressed to **First-tek**, not AgreementMitra - confirm which
entity holds the contract and credentials before production.

> **History.** An earlier revision of this file analysed ZOOP's *public* docs -
> the `prod.aadhaarapi.com` gateway (eSign v3/v4) - and concluded it was
> single-signer, SDK-driven, with a 45-minute token, requiring us to build
> signature chaining and our own invite layer. **All of that is obsolete.** The
> v5 API below is a different, far more capable product and it removes both
> problems. Do not plan against the old gateway docs or the public SDK READMEs.

## 1. What v5 gives us

Everything the product flow needs, natively:

| Need | v5 answer |
| --- | --- |
| Two signers on one document | `signers[]` array in a single `/init` call |
| Owner-then-tenant ordering | `signing_type: "SEQUENTIAL"` (or `"PARALLEL"`) |
| ZOOP emails the parties | `send_invite: true` |
| Signers sign days apart | `txn_expiry_min` (samples use 10080 = **7 days**) |
| Extend a pending request | `POST /v5/increase-expiry-time` |
| Re-send invitations | `POST /v5/send-esign-invitation` (by `group_id`) |
| Track the whole transaction | `group_id`, plus a `request_id` per signer |
| Audit trail | `/v5/fetch/audit-trail`, `/v5/get-audit-trail-report` |
| Cancel | `POST /v5/revise-esign-transaction` |
| Correlate to our records | `task_id` - our own internal id, echoed back |

### Environments

- **Test:** `https://test.zoop.plus/contract/esign`
- **Production:** `https://live.zoop.plus/contract/esign`

Per the proposal, developer/test access is **free and self-serve** via their
dashboard, with a capped transaction count. This is a real advantage over
Leegality, whose Basic Plan is production-only.

### Auth

Two static headers, from their dashboard: **`app-id`** and **`api-key`**.
Env vars only - never committed.

### Init request (the shape we will send)

```
POST {base}/v5/init
{
  "document": { "name": ..., "data": "<base64, max 14 MB>", "info": "<min 15 chars>" },
  "signers": [
    { "signer_name": ..., "signer_email": ..., "signer_city": ...,
      "signer_purpose": ...,                       // mandatory
      "sign_coordinates": [ { "page_num": 1, "x_coord": 100, "y_coord": 300 } ] }
  ],
  "esign_type": "AADHAAR",        // all signers use Aadhaar eSign
  "signing_type": "SEQUENTIAL",   // default PARALLEL
  "send_invite": true,            // default false - ZOOP emails the signers
  "txn_expiry_min": 10080,
  "response_url": "<our webhook>",
  "redirect_url": "<post-signing landing page>",
  "task_id": "<our agreement id>",
  "email_template": { "org_name": "AgreementMitra", "cc": [...] }
}
```

Optional per-signer evidence flags exist (`location_capture`, `photo_capture`,
default false). **Leave them off** unless legal asks - they widen the PII
surface for marginal benefit.

### Init response

```
{ "requests": [ { "request_id", "signer_name", "signer_email",
                  "signing_order", "signing_url" } ],
  "group_id": ..., "success": true,
  "webhook_security_key": "<UUID>",
  "request_timestamp": ..., "expires_at": ... }
```

Three ways to start a signer's journey: the returned **`signing_url`**, the
**frontend SDK** (TAB/REDIRECT), or **`send_invite`** letting ZOOP email them.
We will use `send_invite`, and keep `signing_url` as the resend/fallback path.

### Status

`GET /v5/fetch/group?group_id=...` (or `/v5/fetch/request?request_id=...`).
`transaction_status` is `PENDING -> INPROGRESS -> SIGNED`. The group response
carries `latest_signed_url` (signed so far) and **`complete_signed_url`** (all
signatures present) - the latter is what we store.

### Webhook

ZOOP POSTs to `response_url`. Payload carries `request_id`, `group_id`,
`success`, `response_code`/`response_message`, `result.document.signed_url`
(valid 24h), `result.signer` (including `fetched_name`, `given_name`, `email`,
`city`, `postal_code`, `name_match_score`), `result.other_signers[]`, and
`auth_mode`. The ESP is named in `issued_by` (samples show NSDL).

## 2. How it maps onto our seam

Near-perfect fit. `EsignProvider` was designed for exactly this shape:

| `EsignProvider` | v5 |
| --- | --- |
| `createSignRequest(SignRequest)` | `POST /v5/init` -> `SignSession(group_id, [InviteeSession(email, signing_url, expires_at, request_id)])` |
| `getStatus(providerDocumentId)` | `GET /v5/fetch/group?group_id=` -> per-signer statuses |
| `download(providerDocumentId)` | `complete_signed_url` + `/v5/fetch/audit-trail` |
| `verifyWebhook(payload)` | **needs a signature change** - see M1 |

Our multi-invitee `SignRequest`, the per-invitee `InviteeStatus` sub-state model,
the FSM aggregation rule, the reconciliation job, and webhook-as-a-trigger all
survive untouched. **This is a single adapter class plus one interface change.**

## 3. What still needs design work

### M1. Webhook verification is a per-transaction header (interface change)

ZOOP sends an HTTP header **`webhook-security-key`**, which must equal the
`webhook_security_key` returned by that transaction's `/init`. Two consequences:

1. **It is a shared secret in a header, not an HMAC over the body.** It proves
   the caller knows the key; it does **not** bind the key to the payload. Anyone
   holding the key can forge any payload for that transaction. Weaker than
   Leegality's `HMAC-SHA1(documentId, secret)`.
2. **The key is per-transaction**, so the adapter cannot verify from config
   alone - it must look the key up by `group_id`.

Our existing design absorbs (1) cleanly: `SigningRequestService.completeDocument`
already ignores the webhook body and re-reads authoritative state via
`getStatus`. Keep that discipline - it is now load-bearing, not belt-and-braces.

For (2), `verifyWebhook(String payload)` must change, because the value lives in
a **header** and the secret is per-transaction. Split it: the adapter parses the
untrusted `group_id` from the body, the module loads that transaction's key, then
the adapter compares. Store the key **encrypted at rest** - it is a credential -
and compare in **constant time**.

### M2. Signature placement: coordinates, with an unusual origin

We emit `esign:<role>` text anchors
([TemplateCompiler.java:337](backend/src/main/java/in/agreementmitra/documents/template/TemplateCompiler.java#L337));
v5 wants `page_num` + `x_coord` + `y_coord`.

The anchor renders as real visible text (9px grey,
[TemplateCompiler.java:656](backend/src/main/java/in/agreementmitra/documents/template/TemplateCompiler.java#L656)),
so a PDFBox `PDFTextStripper` subclass can locate it and convert. That
translation belongs **in the ZOOP adapter**, so `documents` never learns about
ZOOP.

**Read this twice:** ZOOP's docs state coordinates are measured from the
**bottom-right** corner - `x_coord` is distance from the **right** edge,
increasing leftward; `y_coord` is distance from the bottom. PDF's native origin
is bottom-**left**, so the x axis must be mirrored (`page_width - pdfbox_x`).
Getting this wrong puts signatures in the wrong place with no error, so verify
visually on the first test document rather than trusting the arithmetic.

`page_num` semantics: `0` = all pages, `-1` = last page, `>=1` = that page.

### M3. Aadhaar name matching

`signer_name` is matched against the Aadhaar record and the webhook returns a
**`name_match_score`**. Our capture form does not promise Aadhaar-exact names.
`InviteeStatus.REJECTED` already models the failure, but the UX needs a
name-correction and re-invite loop. Decide what `name_match_score` threshold we
treat as acceptable - that is a policy question, not a technical one.

### M4. Artifact host allowlist

`LeegalityEsignProvider.requireProviderHost` pins artifact URLs to the single
configured API host. ZOOP's `signed_url` / `complete_signed_url` are expiring
public links likely on a different host (`esign.zoop.plus` appears in samples).
The pin must become a **configured allowlist**. Keep it an allowlist - do not
weaken it to "any https URL the vendor sent".

### M5. Payload size

`document.data` is base64, **max 14 MB**. Our stamped PDF now includes a scanned
SHCIL certificate page (`manual-estamp-upload`), so this is a real ceiling rather
than a theoretical one. Bound the scan at intake and check the encoded size
before calling `/init`.

## 4. Commercial

Rate card (per transaction, INR): Aadhaar eSign **10 / 9 / 8** on Lite / Plus /
Advance. Prepaid packs: Lite Rs.35,000 (no validity), Plus Rs.99,000 (no
validity), Advance Rs.149,000 (**12-month validity**). Zero setup fee. One pack
is consumable across ZOOP products.

**Start with Lite.** It buys 3,500 signatures (~1,750 two-party agreements) and
never expires. Advance's Rs.2/signature saving needs ~9,300 agreements a year to
beat its 12-month expiry risk.

**Billing trigger - answered by the docs.** The webhook carries
`metadata.billable`: `"Y"` on success, `"N"` on failure (the sample failure is
`Invalid OTP`). So charging follows a **completed signature**, not the `/init`
call, and abandoned or failed attempts appear not to be billed. Worth confirming
in writing, but it removes the concern that our design would burn credit on
abandoned sessions.

Against Leegality's Rs.25/signatory: a two-party agreement costs **Rs.20 vs
Rs.50**. Stamping is no longer part of this comparison - it goes in-house via
SHCIL (`manual-estamp-upload`), which recovers roughly Rs.92/agreement of vendor
margin but converts it into staff time nobody has measured yet. Measure that
loop on the first fifty agreements.

## 5. Remaining questions for ZOOP

### Test-environment tracer: NOT YET RUN

The adapter is implemented and green against WireMock, but **no call has ever
been made to a real ZOOP host** - no account exists and no credentials are
configured in this repo. The tracer below is a **manual gate** and must pass
before ZOOP is enabled anywhere beyond the test host.

| Step | Status |
| --- | --- |
| Free staging signup; `ZOOP_APP_ID` / `ZOOP_API_KEY` set from env | **not done** |
| One real `/v5/init` (2 dummy Aadhaar signers, SEQUENTIAL, `send_invite`) | **not done** |
| **Visual check that both signatures land in their signature zones** | **not done** |
| Both invitation emails arrive; 7-day expiry honoured | **not done** |
| Webhook arrives with `webhook-security-key`; transaction reaches SIGNED | **not done** |

The visual check is the acceptance criterion for the coordinate mapping. Our unit
tests pin the mirroring (`EsignAnchorMappingTest`) and the adapter refuses rather
than guessing when an anchor is missing, but an assertion over our own arithmetic
cannot detect a wrong *convention* - only a rendered document can.

Record the outcome in this section when the tracer runs.

### Open questions

Small, and none of them block starting:

1. Confirm in writing that failed/abandoned transactions are not billed
   (`metadata.billable: "N"`).
2. Is the **ESP eSign response XML** retrievable, or is `/v5/get-audit-trail-report`
   the definitive legal artifact for a dispute?
3. Exactly how long is `complete_signed_url` valid? (Webhook says 24h for
   `signed_url`; confirm for the complete document.)
4. Is `signing_url` returned by default, or is it the "configurable from our end"
   option the docs mention?
5. Any rate limits on `/init` and the fetch endpoints?
6. Is `txn_expiry_min` capped?

## 6. Bottom line

**Go.** The v5 API supports the intended flow directly: one `/init` with both
signers, `SEQUENTIAL` ordering, ZOOP emailing the parties, a 7-day window, and a
real audit trail. Pricing is well under Leegality's and test access is free and
self-serve.

The integration is **one adapter class plus one interface change** (webhook
verification). The two hard problems in the earlier analysis - signature chaining
and building our own invite layer - do not exist against this API.

Sequencing: `manual-estamp-upload` should land first, since eSign now depends on a
stamp being attached. The first real task is a **test-environment tracer**: one
`/init` with two Aadhaar signers, `send_invite: true`, and a visual check that
both signatures land on the correct anchors (M2's mirrored x axis is the thing
most likely to be wrong).
