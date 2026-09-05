# Domain, Website and Email Setup: agreementmitra.com

Runbook for putting the registered domain to work: the public site on Cloudflare
Pages, and mail on Zoho Mail. Everything here needs console access to Cloudflare
and to Zoho, so it is written as steps for a human, not as code.

**Status:** not yet executed. Nothing below has been applied to the live domain.

- Registrar / DNS: **Cloudflare**
- Mail: **Zoho Mail, Forever Free plan** (up to 5 users, one domain)
- Site: **Cloudflare Pages**, built from `frontend/`

> **Verify every hostname against the setup wizard as you go.** Zoho routes new
> accounts to a regional data centre, and the MX hostnames, the SPF `include:`,
> and the verification token all differ between them (`zoho.in` for the India DC,
> `zoho.com` for the US DC, and so on). The values in this doc assume the **India
> DC**, which is what you get signing up through `zoho.in`. If the wizard shows you
> something different, the wizard is right.

---

## 0. Order of operations

Do email first. DNS propagation and Zoho's domain verification are the slowest
steps, and neither depends on the site being deployed.

1. Create the Zoho account and verify the domain (section 1)
2. Add the mail DNS records in Cloudflare (section 2)
3. Deploy the site to Cloudflare Pages and attach the domain (section 3)
4. Decide how `/api` reaches the backend (section 4) -- **blocking for `/start`**
5. Verify (section 5)

---

## 1. Zoho Mail: create the account

1. Go to `https://www.zoho.in/mail/` and choose **Sign Up Now** -> the
   **Forever Free Plan** (it is below the paid tiers; the page pushes the paid
   plans first). Free gives 5 users, 5 GB each, webmail and the mobile apps.
   IMAP/POP and a desktop client are paid-tier features -- worth knowing before
   anyone tries to wire this into Outlook.
2. Choose **Sign up with a domain I already own** and enter `agreementmitra.com`.
3. Zoho gives you a **domain verification** record. Take the TXT method: it will
   look like

   | Type | Name | Content |
   |---|---|---|
   | TXT | `@` | `zoho-verification=zbNNNNNNNN.zmverify.zoho.in` |

   Add it in Cloudflare (section 2 explains where), then come back and click
   **Verify**.
4. Create the first mailbox. Use **`hello@agreementmitra.com`** -- it is the
   address already published on the website and in the site's structured data
   (`frontend/src/views/LandingPage.vue`, `frontend/index.html`). If you change
   it, change it in both of those files too; a test asserts the page only ever
   links one address.
5. Once the domain is verified, add aliases rather than burning user seats. On the
   free plan each **user** is one of your five, but **aliases are free and
   unlimited**. Suggested: `support@`, `legal@`, `noreply@`, and `dmarc@` all as
   aliases on the `hello@` mailbox.

---

## 2. Cloudflare DNS records

In the Cloudflare dashboard: select `agreementmitra.com` -> **DNS** -> **Records**.

> **Turn OFF Cloudflare Email Routing before you start.** If Email Routing is
> enabled (Cloudflare sometimes offers it on a new zone), Cloudflare installs its
> own MX records and will fight yours. Zoho and Email Routing cannot both own the
> domain's mail. Go to **Email** -> **Email Routing** and disable it.

### Mail records

| Type | Name | Priority | Content | Proxy |
|---|---|---|---|---|
| MX | `@` | 10 | `mx.zoho.in` | DNS only |
| MX | `@` | 20 | `mx2.zoho.in` | DNS only |
| MX | `@` | 50 | `mx3.zoho.in` | DNS only |
| TXT | `@` | -- | `v=spf1 include:zoho.in ~all` | DNS only |
| TXT | `zmail._domainkey` | -- | `v=DKIM1; k=rsa; p=<key Zoho generates>` | DNS only |
| TXT | `_dmarc` | -- | `v=DMARC1; p=none; rua=mailto:dmarc@agreementmitra.com; fo=1` | DNS only |

Notes on each:

- **MX** cannot be proxied; Cloudflare will not offer the orange cloud. That is
  expected and does not conflict with the website records being proxied.
- **SPF**: you may have **only one** SPF TXT record on the domain. If one already
  exists, merge the `include:` into it rather than adding a second -- two SPF
  records is a hard fail, not a warning.
- **DKIM**: generate this inside Zoho first (**Admin Console** -> **Domains** ->
  `agreementmitra.com` -> **Email Configuration** -> **DKIM** -> **Add**). Zoho
  gives you the selector (`zmail` by default) and the public key. Paste the key in
  as-is. Cloudflare handles the string splitting that long DKIM keys need.
- **DMARC**: start at `p=none`. It reports without quarantining, so a
  misconfigured SPF or DKIM does not silently black-hole your mail on day one.
  Once the `rua` reports come back clean for a couple of weeks, tighten to
  `p=quarantine` and later `p=reject`. Do not start at `p=reject`.

### Website records

Cloudflare Pages adds these for you when you attach the custom domain in section
3. You should not need to create them by hand. If you do, it is a `CNAME` at `@`
and at `www` pointing to `<project>.pages.dev`, both **proxied** (orange cloud).

---

## 3. Deploy the site to Cloudflare Pages

The repo already contains everything Pages needs: `frontend/public/_redirects`
(SPA fallback), `frontend/public/_headers` (security + cache headers),
`robots.txt` and `sitemap.xml`.

**Cloudflare dashboard -> Workers & Pages -> Create -> Pages -> Connect to Git.**

| Setting | Value |
|---|---|
| Root directory | `frontend` |
| Build command | `npm ci && npm run build:only` |
| Build output directory | `dist` |
| Environment variable | `NODE_VERSION` = `20.19.0` |

Then **Custom domains** -> add `agreementmitra.com` and `www.agreementmitra.com`.

**Why `build:only` and not `build`:** `npm run build` runs `security:scan` first,
which shells out to `osv-scanner`. That binary is not present in the Pages build
image, and the gate is deliberately fail-closed, so `npm run build` cannot
succeed there. Using `build:only` means **the Pages build is not a security
gate** -- the dependency scan has to run in CI instead. That is already the
planned CR-7 work (see `CLAUDE.md`, "Testing & Scanning"). Do not read a green
Pages deploy as a passed scan.

---

## 4. The `/api` problem -- read before pointing the domain at Pages

The SPA calls the backend at a **same-origin** `/api` (`frontend/src/api/client.ts`,
`const BASE = "/api"`). Cloudflare Pages serves static files only. So on a plain
Pages deploy:

- `/` -- the marketing page renders perfectly. It makes **no** network calls by
  design, so it does not care whether the backend exists.
- `/start` -- the app shell renders, then the template list fails and the user
  sees "Could not load templates." It degrades visibly rather than crashing, but
  it is still a dead end.

`frontend/public/_redirects` deliberately does **not** rewrite `/api`, so those
calls fail loudly instead of being handed `index.html` by the SPA fallback.

Pick one before launch:

- **(a) Ship marketing-only first.** Point the domain at Pages now and hold the
  app back until the backend is deployed. Cheapest, and honest -- the landing
  page's status board already tells visitors the paid rails are still in
  integration. Requires making `/start` show a waitlist instead of a broken
  picker.
- **(b) Cloudflare Worker proxy (recommended once the backend is up).** Add a
  Worker on the route `agreementmitra.com/api/*` that forwards to the Spring Boot
  origin. Keeps one origin, so no CORS and the session cookie keeps working.
  Worker routes are evaluated before Pages, so it takes precedence over
  `_redirects`.
- **(c) Serve `dist/` from Spring Boot.** One origin, one deploy, no Worker. But
  it couples every marketing copy change to a backend release, which is the thing
  a separate Pages project is meant to avoid.

**This is the one decision still open.** Everything else in this doc can be done
today.

---

## 5. Verify

Mail (from any shell; give DNS 15-30 minutes first):

```sh
dig +short MX   agreementmitra.com
dig +short TXT  agreementmitra.com          # expect exactly one v=spf1 record
dig +short TXT  zmail._domainkey.agreementmitra.com
dig +short TXT  _dmarc.agreementmitra.com
```

Then send a real message **from** `hello@agreementmitra.com` **to** a Gmail
address. In Gmail, open the message -> **Show original**. You want
`SPF: PASS`, `DKIM: PASS`, `DMARC: PASS`. Anything less and mail to Gmail and
Outlook will land in spam, which for identity/legal infra is worse than useless.

Site:

```sh
curl -sI https://agreementmitra.com/          | head -20   # 200, HSTS present
curl -sI https://agreementmitra.com/start     | head -5    # 200 via SPA fallback
curl -s  https://agreementmitra.com/robots.txt
curl -s  https://agreementmitra.com/sitemap.xml
```

Then paste the homepage into Google's Rich Results Test to confirm the FAQ and
Organization structured data parse.

---

## 5b. Outbound (transactional) email: Zoho Mail in dev, ZeptoMail in production

**Decided 2026-08-03** (`signed-delivery-and-closure`, design D7). Everything in
sections 1-2 above provisions a **mailbox** -- a place humans read mail. The app
also has to **send** the signed agreement to both parties, which is a different
job with different failure modes.

| | Development | Production |
| --- | --- | --- |
| Service | Zoho Mail, Forever Free | **Zoho ZeptoMail** |
| Host | `smtp.zoho.<dc>` | `smtp.zeptomail.<dc>` |
| Ports | 465 (SSL) / 587 (STARTTLS) | 465 (SSL) / 587 (STARTTLS) |
| Username | the mailbox address | the literal `emailapikey` |
| Password | the mailbox credential | a ZeptoMail send token |

Both speak SMTP, so the backend ships **one** SMTP adapter and the upgrade is
**host plus credentials, not a new integration**.

> **Match the data centre.** `.in` vs `.com` differs per account, exactly as the
> MX hostnames in section 2 do. A mismatch fails authentication in a way that
> looks precisely like a wrong password.

### The free mailbox is a development-only sender

State it plainly, because the failure is silent and reputational:

- **Low outbound caps.** It is a mailbox service, not a transactional sender.
- **No bounce reporting.** Plain SMTP tells us the provider *accepted* a message,
  never that it *arrived*. A hard bounce is invisible, so `SENT` in our records
  means "handed to the provider" and nothing stronger. ZeptoMail's **bounce
  webhook** closes this in production; it is additive and not built yet.
- **Sending real user mail from it would put the human mailbox's reputation
  behind bulk delivery** -- which is the same argument as the transactional
  subdomain follow-up below.

It **must not carry real user traffic.**

### Before production sending works

1. **Start the ZeptoMail account review early.** ZeptoMail enforces a
   transactional-only policy and reviews new accounts via a **Customer
   Validation form**, typically **2-3 business days**. It is a lead-time item,
   not a switch. Discovering it on launch day stalls sending.
2. **SPF and DKIM for the sending domain against ZeptoMail**, verified aligned
   before the first production send.
3. **Wire the ZeptoMail bounce webhook** into the delivery capability's existing
   permanent-failure path.

### Configuration

The app **defaults to a stub that sends nothing**. Real sending is configuration,
never a default, and the production host is never a default either -- a
half-configured deployment sends nothing rather than sending a document naming
both parties, the property and the money to the wrong place.

| Env var | Default | What it is |
| --- | --- | --- |
| `MAIL_PROVIDER` | `stub` | `stub` (capture in memory, send nothing) or `smtp` |
| `MAIL_FROM` | empty | the envelope/from address; unset refuses to send |
| `MAIL_FROM_NAME` | `AgreementMitra` | display name |
| `MAIL_MAX_ATTACHMENT_BYTES` | `10485760` (10 MiB) | raw-PDF attachment ceiling |
| `MAIL_SMTP_HOST` | empty | never defaults to a production host |
| `MAIL_SMTP_PORT` | `587` | 465 for implicit SSL |
| `MAIL_SMTP_USERNAME` | empty | mailbox address, or `emailapikey` for ZeptoMail |
| `MAIL_SMTP_PASSWORD` | empty | mailbox credential, or the ZeptoMail send token |
| `MAIL_SMTP_SSL` | `false` | implicit SSL (port 465) |
| `MAIL_SMTP_STARTTLS` | `true` | STARTTLS, enforced as *required* (port 587) |
| `MAIL_SMTP_TIMEOUT` | `PT20S` | connect/read/write bound |

Credentials come from **environment variables only** and are never committed.

**The attachment ceiling is derived from the assembled message, not the raw
file.** ZeptoMail caps a message at **15 MB total** -- headers, body and
base64-encoded attachments combined -- and base64 inflates binary by roughly a
third. 10 MiB raw assembles to about 13.4 MB, leaving real headroom. Measuring
the raw file against 15 MB instead would produce a message the provider rejects
*after* we had already recorded it as sent. Above the ceiling, delivery falls
back to a notification pointing at the in-app copy -- never a truncated
attachment. The value was chosen alongside the 8 MiB certificate-scan ceiling at
e-stamp intake, which is the one input to the signed PDF with no natural size
bound.

Delivery retry (`signing.delivery.*`) is bounded on every axis:

| Env var | Default | What it is |
| --- | --- | --- |
| `SIGNING_DELIVERY_RETRY_ENABLED` | `true` | the scheduled retry sweep |
| `SIGNING_DELIVERY_INTERVAL` | `PT5M` | how often the sweep runs |
| `SIGNING_DELIVERY_INITIAL_DELAY` | `PT2M` | delay before the first sweep |
| `SIGNING_DELIVERY_MAX_ATTEMPTS` | `5` | attempts per recipient before failing |
| `SIGNING_DELIVERY_INITIAL_BACKOFF` | `PT1M` | delay before the second attempt |
| `SIGNING_DELIVERY_MAX_BACKOFF` | `PT1H` | ceiling on the doubling |
| `SIGNING_DELIVERY_BATCH_SIZE` | `50` | rows per sweep |

An exhausted delivery lands in `FAILED` with a reason and appears on the staff
delivery view (`GET /api/staff/deliveries/{agreementId}`), where a human can
re-send it -- which is the outcome that actually gets the document to the party.

> **Before deploying anywhere holding real signed agreements:** confirm the
> deploy will not fire a burst of delivery emails for agreements that completed
> *before* delivery existed. The migration deliberately leaves pre-existing rows
> open and creates no delivery records for them, but confirm it against the
> actual data.

---

## 6. Follow-ups, not done here

- **Google Search Console + Google Business Profile.** Both are prerequisites for
  the organic strategy in `docs/GO-TO-MARKET-HYDERABAD.md`, and the Business
  Profile in particular is what surfaces you for "rental agreement hyderabad".
  Verify the domain in Search Console via a Cloudflare TXT record and submit the
  sitemap.
- **Tighten DMARC** from `p=none` once reports are clean.
- **A transactional sending domain.** Signed-agreement emails should go out over a
  dedicated subdomain (say `mail.agreementmitra.com`) with its own SPF/DKIM, so a
  deliverability problem in bulk sending never poisons the reputation of the
  human mailbox at the apex.
- **Privacy policy and terms.** The site collects personal data through the
  builder and will handle Aadhaar-linked signing. Both pages are needed before
  taking real users, and both are required by the eSign vendors during
  onboarding.
