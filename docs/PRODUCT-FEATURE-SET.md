# Product Feature Set & Positioning

**Status:** Decision-anchoring input for UI scoping — **not committed work.**
**Origin:** Competitor research, 2026-06-22 (Indian online rental-agreement / e-stamp /
Aadhaar-eSign market). Feeds the upcoming frontend signing-flow OpenSpec proposal.
**Related:** [ARCHITECTURE.md](ARCHITECTURE.md) · [future-features/index.md](future-features/index.md)

This file consolidates the market feature set, our chosen positioning, and a feature
tiering (MVP / Parity / Later) mapped to what the backend already supports. It exists so the
*why* behind the UI scope isn't lost. When a tier graduates into a build, it becomes an
OpenSpec change under `openspec/changes/`.

> **Confidence caveat (read first).** Competitor data is uniformly **medium** confidence —
> pricing and login details were often inferred from visible page flows, not published grids.
> Treat exact rupee figures and "login required" gates as directional. Claims that failed
> 3-vote adversarial verification are listed under [Caveats](#caveats--refuted-claims) and
> must **not** be relied on.

---

## 1. Positioning — the differentiator (decided)

**Headline: Trust + correctness, with a vernacular moat.**

The core feature bundle (form → stamp → Aadhaar eSign → PDF) is **commoditised at ~₹399**
(verified across two independent competitors). Competing on price or raw speed is a losing
or undifferentiated game — speed in particular *requires* the very integrations we're
deferring, and incumbents already market "instant" while reviews show it lags.

Our durable moat is what the architecture **already** committed to before this research
(see [ARCHITECTURE.md](ARCHITECTURE.md) "Product"): a multi-state **legal-logic rules
engine** that auto-generates jurisdiction-correct agreements, and **vernacular/multilingual**
document generation (the `documents` module uses Chromium/Noto precisely because only
Chromium shapes Indic scripts correctly). Competitors treat the document as a fill-in-the-
blank PDF; our stack is built to treat it as *correct legal output in the tenant's language*.
That is the hard-to-copy edge.

For the **MVP**, that positioning is expressed in the two ways that need **zero new
external integrations**:

- **Radical transparency & trust** — all-in price (stamp duty computed) shown *before*
  payment, live status tracking, a visible audit trail. The field is notably opaque here, so
  this is a real wedge and it's software-only.
- **Frictionless no-login self-serve** — a guest wizard, no account required.

Vernacular, multi-state correctness, and WhatsApp then become post-MVP layers that *deepen*
the same positioning rather than pivoting it.

---

## 2. The market splits into two service models

| Model | Who | How it works |
|---|---|---|
| **Self-serve automated** | Vakeel360, RentPaper, eSahayak (core) | Form wizard → pay → instant PDF, no human in the loop. |
| **Assisted-manual** | Book My Agreement, eRentAgreement, Housewise | Light form/phone/WhatsApp intake → a human drafts it → an executive does a **doorstep biometric** Aadhaar registration. The path for non-tech-savvy users. |

NoBroker and AgreementKart are **hybrids** leaning assisted (self-serve front-end, ops/legal
fulfillment + delivery).

**Login is not table-stakes** — the field genuinely splits. Vakeel360 runs "100% free, no
signup" (verified); NoBroker and eSahayak gate behind an account. A new entrant should not
copy the super-app login model first.

---

## 3. Feature tiering → mapped to backend

Priority reflects the positioning above. **The MVP-vs-later integration line is deliberately
left to scoping** (a sellable product needs real eSign + real e-stamp; the existing stack is
stub eSign + synthetic stamp). Tiers below rank *product* priority; the OpenSpec proposal
decides how many integrations MVP absorbs.

| Feature | Market status | Backend today | Tier |
|---|---|---|---|
| Structured self-serve agreement form | table-stakes | ✅ `POST /api/agreements`; **no UI** | **MVP** |
| Upload-your-own-draft | near-table-stakes (often discounted) | ✅ `POST /agreements/{id}/draft`; no UI | **MVP** |
| No-login / guest start | split (not table-stakes) | ✅ endpoints unauthenticated; identity proven at eSign | **MVP** |
| All-in price + status + audit-trail transparency *(differentiator)* | weak across field | partial (audit trail exists) | **MVP** |
| Remote Aadhaar OTP eSign | table-stakes | ✅ `EsignProvider`/Leegality seam (stub) | **MVP** (UI) |
| Instant signed-PDF | table-stakes | ✅ webhook + signed-artifact storage | **MVP** (UI) |
| Status tracking endpoint + retrieve | table-stakes | ❌ no status / signed-artifact fetch endpoint | **MVP/Parity** *(backend gap)* |
| State-specific stamp-duty calculator | table-stakes | ⚠️ synthetic, Karnataka-only | **Parity** |
| Genuine e-stamp procurement + denomination | table-stakes (paid) | ⚠️ synthetic stamp only | **Parity** |
| WhatsApp eSign-invite + signed-PDF delivery | infra-standard | ✅ Leegality "WhatsApp Pings" (our chosen adapter) | **Parity** |
| Thin assisted intake (form → ops queue) | differentiator (low-tech) | ❌ | **Parity** *(software only; not the ops business)* |
| Ownership auth + rate-limit on signing endpoints | — (security debt) | ❌ deferred to `signing-auth` | **Parity** *(must not force account on guest path)* |
| Multi-state correctness (rules engine) *(moat)* | growth lever | ❌ `rules` module future | **Later** |
| Vernacular / bilingual agreements *(moat)* | rare (Eng/Hindi at best) | ❌ (documents module ready) | **Later** |
| WhatsApp guided/structured intake | **does not exist in market** | ❌ | **Later** *(true differentiator; Meta API-gated)* |
| Doorstep delivery / police verification | add-on | ❌ | **Later** *(partner, not build)* |
| Logged-in dashboard (manage portfolio) | retention | ❌ | **Later** *(never gate guest creation)* |

---

## 4. Intake-model decisions

- **No-login self-serve leads.** Identity is proven via Aadhaar OTP at signing, so an
  account is pure friction. Backend is already unauthenticated; keep guest creation open.
- **Assisted intake is a fast-follow, as a thin software queue** (a short "we'll prepare it
  for you" form that drops into an ops queue). Do **not** build the heavy assisted apparatus
  (human drafting desks, doorstep biometric kits, courier logistics) — that's an operations
  business incumbents already own.
- **WhatsApp is a notify + eSign-invite + signed-PDF *delivery* channel, not a chatbot.**
  Leegality (our chosen first adapter) already has "WhatsApp Pings" that sends the eSign
  link, reminders, and final PDF over WhatsApp (verified) — largely config on infra we're
  already integrating. A *guided structured* WhatsApp intake is a genuine **Later**
  differentiator (nobody has it), but Meta's 2026 Business API rules forbid free-form bots,
  so it must be a structured flow and a separate build.

---

## 5. Competitor snapshot

Pricing is "starting" / entry, **stamp duty extra** unless noted. Confidence medium.

| Competitor | Login | Fulfillment | Entry price | E-stamp + duty calc | eSign | Delivery | Notable |
|---|---|---|---|---|---|---|---|
| **NoBroker** | account (mobile+OTP) | hybrid (ops prints/delivers) | ₹399 + stamp | yes, per-state | Aadhaar remote + physical + doorstep biometric | instant soft + 3–4 day hard | 3 creation modes incl. upload-draft; 150+ cities |
| **AgreementKart** | guest (lead form) | assisted (team contacts you) | ₹399 (−₹100 upload) | denominations ₹100/200/500 | Aadhaar | doorstep 24–72h | 4 types (res/comm/coworking/PG); 28-state SEO |
| **LegalDesk** | unknown (likely at pay) | hybrid (DIY + Print'n'Deliver) | ₹238 / ₹349; upload ₹99 | yes, auto by location | Aadhaar OTP via eSignDesk | self-print or courier | live preview; wide template library |
| **eSahayak** | account (free signup) | hybrid (procures stamp) | ₹399 (sample invoice ₹473.10) | yes, 28 states + 8 UTs | Aadhaar OTP, up to 5 tenants | instant soft + optional doorstep | 120+ lawyer-verified templates; in-app tracking |
| **RentPaper** | optional/guest* | self-serve automated | ₹199 + duty | yes, **CG & KA only** | Aadhaar via SignDesk (+₹299) | instant + doorstep (+₹100) | bilingual (Eng/Hindi); police verify (+₹499) |
| **Vakeel360** | **none (no signup)** | self-serve automated | free generator | state info only | — | instant DOCX | proof a true no-login path works |

\* RentPaper's homepage contradicts itself ("No account needed" vs Step 01 "Register Free");
its guest-checkout claim was **refuted** in verification — see Caveats.

---

## 6. Caveats & refuted claims

Claims that **failed** 3-vote adversarial verification — do **not** build on these:

- **RentPaper offers a genuine no-login guest checkout** — refuted (0-3). "Guest checkout
  works in self-serve" rests on **Vakeel360** (verified 3-0), not RentPaper.
- **ZoopSign offers WhatsApp eSign-from-chat** — refuted (0-3). WhatsApp-eSign evidence
  stands on **Leegality Pings** and **Zoho Sign** (both verified 3-0).
- **NoBroker requires login** and **NoBroker fulfillment is fully manual** — both
  contested/refuted. Do not cite NoBroker as proof that login or manual ops is mandatory.

Other caveats: pricing is "starting" and rarely itemised publicly; add-on rupee amounts are
mostly unpublished; the core bundle is commoditised, so the differentiator must come from
trust/correctness/vernacular, not features.

---

## 7. Open questions for scoping

1. **MVP integration line** — pilot on stub eSign + synthetic stamp (UI-complete, not yet
   transacting) vs. allow live Leegality eSign only? *(Decided at scoping.)*
2. **Backend read-side gaps** — a status endpoint and a signed-artifact fetch endpoint don't
   exist; the UI can't honestly show "signed" or offer download without them. Sequence vs UI?
3. **signing-auth without accounts** — what request-scoped model (per-agreement token / magic
   link / signer-email verification) closes the unauthenticated-endpoint debt **without**
   forcing account creation and regressing the no-login decision?
4. **Launch states** — only Karnataka synthetic stamp exists today; does the `rules` module
   own per-state stamp duty, or is it bought from the e-stamp vendor?
5. **Guest payment** — is true guest checkout (incl. payment) acceptable to the payment
   provider and from a fraud/abuse standpoint?

---

## 8. Sources

NoBroker (`nobroker.in/rental-agreement`, `/blog/nobroker-rent-agreement`), AgreementKart
(`agreementkart.com/rental-agreement`, Bangalore + doorstep pages), LegalDesk
(`legaldesk.com/documents/rental-agreement`, `/quick-rental-agreement`, `/print-n-deliver`),
eSahayak (`esahayak.io/service/rent-agreement`; invoice via Scribd), RentPaper
(`rentpaper.in`), Vakeel360 (`vakeel360.com/rent-agreement-generator`), assisted players
(`bookmyagreement.co.in`, `erentagreement.com`, `housewise.in`), WhatsApp-eSign infra
(`leegality.com/features/whatsapp-pings`, `zoho.com/sign`, `zoopsign.com`, `signdesk.com`),
and Meta WhatsApp Business API 2026 policy. Full per-claim source list in the research run.

---

## 9. Brand & UI foundation (decided)

Scaffolded ahead of the frontend signing-flow proposal; lives in `frontend/` (theme tokens,
logo, header). Expresses the §1 positioning visually — warmth as *tone*, correctness as
*substance*.

- **Theme — warm-trust.** Deep-indigo "trust" + saffron "mitra" warmth + a reserved
  verified-green for status/signed/audit (the transparency wedge made visible) + warm-gray
  neutrals. The palette is a **single source of truth**: CSS vars in `src/style.css` (RGB
  channels) consumed by Tailwind and readable by the PDF templates. Bilingual font stack
  (Inter + Noto Sans Devanagari) ties the UI to the same Chromium/Noto Indic-shaping
  decision the `documents` module already made.
- **Logo.** Two-tone check — white tick + saffron sweep — on an indigo tile; camelCase
  wordmark "Agreement"(indigo)·"Mitra"(saffron). Mark / wordmark / favicon in
  `frontend/src/assets/`.
- **Tagline — calibrate claims to what the backend can prove** (extends §1 and §6). "Mitra"
  raises the trust expectation, so warmth must not outrun substance, and any
  legal-correctness claim is liability / unauthorized-practice-of-law territory until the
  `rules` engine funds it.
  - ✅ safe now: **"Rental agreements made simple"** (current), "Your rental-agreement mitra"
  - ⚠️ earn first (needs rules engine + multi-state): "Legally correct in your state"
  - ❌ never: implies personal legal advice / "we're your lawyers". ("Done right" reads as a
    correctness claim — deliberately avoided.)

**Follow-ups (not yet built):**

- **Self-host fonts** (`@fontsource/inter` + `@fontsource/noto-sans-devanagari`) to drop the
  Google Fonts CDN runtime fetch — small hardening CR (identity infra; aligns with the
  frontend supply-chain posture in CLAUDE.md).
- **Re-skin `SignDemo`** off the legacy `slate` / `blue` utilities onto the `brand` /
  `accent` / `success` / `ink` tokens when the signing-flow UI is built.
- **Outline the wordmark text** for any distributed / print art (in-app it renders live via
  Inter).
