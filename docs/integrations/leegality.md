# Leegality — integration notes

Vendor facts the team needs when planning eSign/stamping work. Captured from
the Leegality Enquiry Team response (2026-06-27). Keep this current as our
account status changes.

## Account & environments — the key constraint

- **Basic Plan is production-only. It is NOT a sandbox / test mode.** Their
  words: _"The self-serve Basic plan operates exclusively on our production
  environment and does not include access to a sandbox or test mode."_
- **To test with dummy data you must request a dedicated developer sandbox
  account** — separately, it is not provisioned by Basic Plan signup. Ask via
  support@leegality.com or the Enquiry Team.
- **Repo policy is sandbox + dummy data only** (this is identity/legal infra).
  Therefore this codebase must NOT be pointed at the Basic Plan production
  endpoint. Live end-to-end testing is blocked until the developer sandbox
  account exists. Until then we run against the WireMock/stub provider.
- Credentials (base URL, auth token, webhook secret) come from **env vars
  only** — never committed. See the `EsignProvider` seam / `LeegalityEsignProvider`.

## Basic Plan (the no-license, pay-per-use plan)

- Zero license fee; pay per use.
- Aadhaar eSign **and** Secure Virtual Sign for collecting signatures.
- Digital stamp papers from **31 States/UTs**, auto-affixed onto documents
  before signing — relevant to our `StampProvider` seam (real stamping becomes
  a vendor swap, not a SHCIL/state-portal build).
- Basic compliance: digital rubber seal, Secure Audit Trail, basic Aadhaar
  eSign verification.
- API integration via **one free workflow**.

## Pricing (COGS reference)

| Item | Price |
| --- | --- |
| License fee | ₹0 |
| Aadhaar eSign | ₹25 per signatory |
| Secure Virtual Sign | ₹15 per signatory |
| Digital stamping | ₹45 per stamp paper + stamp duty + ₹500 delivery / order (**min 30 papers**) |

Against the ~₹399 commoditised bundle in `docs/PRODUCT-FEATURE-SET.md`, the
₹25/signatory eSign cost is the relevant COGS floor; the 30-paper minimum +
₹500 delivery makes per-order real stamping lumpy at low volume — a reason the
synthetic stamp stays for v1 until volume justifies real procurement.

### Digital stamping — how the charge decomposes

The `₹45 per stamp paper` is **Leegality's service fee** (procurement +
auto-affix), charged **on top of** the actual government stamp duty — it is not
the stamp paper's value. Three separate components:

| Component | Goes to | Notes |
| --- | --- | --- |
| Stamp duty (e.g. ₹100) | State govt | Real value of the stamp paper; payable with any vendor. |
| ₹45 / stamp paper | Leegality | Service markup. The "extra" over duty. |
| ₹500 / order | Leegality | Flat delivery fee for the **whole batch**, not per paper. |

Constraint: **minimum order = 30 stamp papers** — you cannot buy one.

### Unit economics (worked example, ₹100 duty assumed)

**Marginal cost per agreement, at volume** (the number for pricing):

```
Stamp duty             ₹100.00
Leegality stamp fee    ₹ 45.00
Delivery (₹500 ÷ 30)   ₹ 16.67
─────────────────────────────
Stamping per agreement ≈ ₹161.67
+ Aadhaar eSign 2 sigs  ₹ 50.00   (owner + tenant @ ₹25)
─────────────────────────────
Full agreement         ≈ ₹211.67
```

At the ~₹399 price point that is ~₹187 gross margin/agreement before
fixed/infra.

**First-order working capital** (the 30-paper minimum bites at low volume):

```
30 × ₹100 stamp duty   = ₹3,000
30 × ₹45 Leegality fee = ₹1,350
1  × ₹500 delivery     = ₹  500
──────────────────────────────
First order total      = ₹4,850  (30 stamp papers pre-stocked as inventory)
```

So a single early customer still costs ₹4,850 cash up front; the ₹161.67 only
holds once all 30 are consumed. Larger batches amortize delivery further (100
papers → ₹5/paper → ~₹150 stamping/agreement) at the cost of more capital tied
up in inventory.

**Caveat:** ₹100 duty is an assumption. Karnataka rental stamp duty is
typically a slab on rent × term, not flat ₹100 — confirm the real duty for
target agreements before treating ₹212 as final.

## Compliance posture (vendor)

ISO 27001:2022 / 27017 / 27018 / 22301 certified; SOC 2 Type 2 attested.
AES-256 at rest (volume + user-specific file keys); TLS 1.2+ enforced in
transit.

## Setup links (from the response)

Setup / account:

- [Sign Up — create Basic Plan account](https://dashboard.leegality.com/sign-up)
- [Getting Started Guide](https://knowledge.leegality.com/category/getting-started)
- [Demonstration Video (product demo)](https://www.youtube.com/watch?v=nxpeRDsQZF4&feature=youtu.be)
- **[API Reference Guide](https://www.leegality.com/api-reference-guide/api-reference-guide)** — the one we need for the `LeegalityEsignProvider` adapter.
- [Full Basic Plan feature list](https://www.leegality.com/pricing#features)

Reference attachments (GTM-buddy share links — may expire; mirror anything we
rely on):

- [Regulatory Process Flow — Aadhaar eSign](https://leegality.gtmbuddy.io/shares/view/id/69490f8c78525f4e31fe8911/69490f8c78525f4e31fe8913)
- [Memorandum on validity of electronic & digital signatures](https://leegality.gtmbuddy.io/shares/view/id/69490f8c78525f4e31fe8911/69490fb7f605f157b9e65021)
- [Leegality Bharatstamp](https://leegality.gtmbuddy.io/shares/view/id/69490f8c78525f4e31fe8911/69490fca75ae5567b8126185)
- [Leegality Bharatstamp v others](https://leegality.gtmbuddy.io/shares/view/id/69490f8c78525f4e31fe8911/69490fdf990d3a49a8a819d9)
- [Digital Stamping Process](https://leegality.gtmbuddy.io/shares/view/id/69490f8c78525f4e31fe8911/69490ff9f605f157b9e656f4)

Support / escalation: support@leegality.com (or the in-product Product Bot).

## Action items

- [ ] Request a **dedicated developer sandbox account** (unblocks Track B —
      live e2e and `leegality-real-stamp`).
- [ ] On receipt: load creds via env, run the live happy-path tracer against
      the stub-equivalent flow.
