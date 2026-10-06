# Lease-expiry reminders — agreed direction

**Status:** direction agreed, **not yet proposed**. No `openspec/changes/` entry exists
for any of it. Scheduling lives in [ROADMAP.md](ROADMAP.md) Track A.
**Origin:** PO/UX exploration 2026-10-06.
**Related:** [PRIVACY-POLICY.md](PRIVACY-POLICY.md) §5 · [TERMS-OF-SERVICE.md](TERMS-OF-SERVICE.md)
§12 · `signing/EmailSender`, `signing/contact/ChannelDispatcher` ·
`documents/template/sets/rental/base.yaml` (`noticePeriodMonths`)

**Mock screens:** https://claude.ai/artifact/SvSyc48KjevsQH68nxcabF
(six artboards: signed page signed-out teaser, opt-in, reminders-on state, My agreements
countdown + panel, the owner reminder email, turn-off-from-email page. Private — ask for
access if the link refuses.)

A signed agreement already holds everything needed to warn people before a lease runs
out: `end_date` is stored on `Agreement`, and every rental deed carries a notice period
(`noticePeriodMonths`, default 1). Missing the notice date costs a tenant a month's rent in
lieu of notice; missing the end date leaves an owner with an unplanned vacancy, or a tenant
holding over with no agreement and no renewal escalation. This feature emails a reminder
before both dates and points the user at the renewal.

---

## 1. Decisions

| Decision | Choice | Why |
|---|---|---|
| Where it lives | A property of a **signed, open** agreement (in `signing`), not a standalone tracker | No new data needed; sits beside the existing draft / signed-PDF delivery |
| Consent | **Opt-in**, explicit unticked checkbox; record party, time and consent-text version | DPDP posture; privacy §5 lists exactly what we email today and reminders are not on it |
| Who can opt in | **Only the signed-in account that owns the agreement** | Reminders go to the account's **Google-verified** email — party emails on `Signer` are unverified and a typo would silently drop a reminder |
| Role | The opt-in asks **"Which party are you?"** (owner or tenant, by name) | The account is not linked to a party row (`claim-bound-to-initiator`), and the copy differs per role |
| Channel | **Email only** in v1 | SMS / WhatsApp are declared but disabled in `DeliveryChannel`; adding one is its own change |
| Schedule | Anchored on the **notice date** (`end − notice`), not fixed day counts: notice − 30 days, the notice date, end − 7 days | "Decide by X" is what saves money; the notice period is already captured |
| Turn off | Per agreement, from the app (signed in) or the email link (no sign-in) | — |

**Accepted v1 limit:** only the claiming account can opt in, so if the owner claimed the
agreement the tenant cannot get reminders. Revisit with `claim-bound-to-initiator`.

**Not a deadline before paid launch.** Opt-in happens any time after signing, so nothing has
to ship before the first paid agreement; the first reminder is ~11 months after signing.

## 2. Constraints the build must respect

- **Unsubscribe link must not change state on GET** (CLAUDE.md). The link opens a confirm
  page; the button POSTs. Add RFC 8058 `List-Unsubscribe` + `List-Unsubscribe-Post` headers
  (one-click is a POST). The link carries a signed, per-subscription token — never the raw
  agreement id (`agreement-capability-token`).
- **Notice period is a template field, not a column.** Copy it onto the agreement at signing
  (or read it from the pinned capture state) so the schedule can never drift from the PDF.
- **Job shape** follows the existing four jobs: package-private `@Component`, gated by
  `@ConditionalOnProperty`, delegates to a service, bounded oldest-first batches, rows claimed
  with `FOR UPDATE SKIP LOCKED`, one row per (subscription, reminder point) so each email is
  sent once. Skip `CLOSED` agreements. Raise the scheduler pool (4 → 5; the yml comment asks
  for it). Links from `delivery.public-base-url`. Log class names only.
- **Opting in after a reminder point has passed** skips the past points; the UI shows the next.
- **Production sending** depends on `zeptomail-production-provisioning` and
  `zeptomail-bounce-webhook` (register). Lower risk here than party emails — the recipient is
  Google-verified — but a bounce is still silent until that row lands.
- **Retention clash:** ToS §12 keeps a signed agreement 3 years from signing; a longer lease
  would be deleted before its reminder. Append to counsel's existing retention question.
- **Legal text:** privacy §5 email list, a ToS clause and an FAQ entry ship in the change that
  starts sending; the consent/lawful-basis question is appended to the existing privacy/DPDP
  counsel gap, not a new row.

## 3. Changes, in dependency order

1. **`lease-countdown`** — copy `noticePeriodMonths` onto the agreement at signing (migration);
   expose notice date / end date in the agreement summary; countdown chips on My agreements
   and the signed status page ("Notice due in N days", urgent style ≤ 7 days, "Lease ended"
   after); "Add to calendar" `.ics` download (a side-effect-free GET). No email, no consent.
2. **`lease-reminders`** — opt-in (party pick, consent record, versioned consent text),
   subscription + sent-reminder tables, schedule calculator, `LeaseReminderJob`, owner and
   tenant email copy with `.ics` attached, turn-off from the app and from the email (token
   page → POST, one-click headers), privacy / ToS / FAQ text. Mock screens 1–6. Needs 1.
3. **`renew-agreement`** — "Renew" creates a new draft prefilled from a signed agreement
   (new term, escalated rent, new stamp duty and eSign). The reminder email's button moves
   from "Start a new agreement" to "Renew". Independent of 2, but 2 is where it pays off.

**Later, not scheduled:** reminders for leases signed elsewhere (a standalone tracker — a
different data model and a marketing play); WhatsApp / SMS reminders; a post-expiry
"your lease has ended" email.
