# Release checklist - payment-confirmation-catch-all-integrity-mapping

What has to be true before this change goes to production, how it gets there, and how to tell it
worked. The deploy procedure itself is `docs/DEPLOYING.md`; this file only adds what is specific
to this change. Tick items as they are done.

## What ships

- **This change:** code only. No migration, no new or changed env var, no frontend change.
- **Riding along:** the branch `feat/order-notifications` is three commits ahead of `main`, so a
  deploy of the merged branch also carries, if they are not in production yet:
  - `38986ed` - `PAYMENT_MODE` template accepts `OPTIONAL`;
  - `91fd214` - staff Discord alert on a paid order (env `STAFF_ALERT_DISCORD_WEBHOOK_URL`);
  - `27e21f9` - surplus payments, **migration V28**, which creates
    `uq_payment_order_provider_payment_id`.
- **Behaviour change visible from outside:** `POST /api/webhooks/razorpay` answers `500` (was
  `202`) when the database refuses to record a payment for a reason other than a duplicate. The
  manual staff confirmation answers `500` (was a false `409`) in the same case.

## 1. Before merging (local)

- [x] Full backend check green: 2008 tests, 0 skipped, 1m41s (2026-10-10).
- [x] Local database has both index names and `lc_messages = en_US.utf8` (2026-10-10).
- [x] Unsigned webhook still rejected with `401`, nothing changed (2026-10-10, against the old
      build on :8090 - re-run on the new build by the script).
- [x] Backend restarted on this branch's code.
- [x] Signed webhook checks pass: `webhook-local-test.sh` (17 checks) with the local webhook
      signing key exported. Covers: non-duplicate refusal -> `500` twice, nothing recorded, clean
      body; normal payment -> `202`, `PAID`; true duplicate -> `202`, nothing recorded.
- [x] Backend log shows one `Payment recording failed for order ****xxxx ... SQL state 22001`
      line per failed delivery, and the 65-character payment id appears nowhere in the log.
- [x] Staff console: recording a payment with amount `10000000000` gives a server error (not
      "reference already used"), the agreement stays unpaid, and the log shows
      `Payment recording failed for agreement ... SQL state 22003` with no amount or reference.
- [x] Staff console: the same reference on two agreements - the second is still refused as reused.
- [x] One sandbox checkout end to end still ends `PAID`.

## 2. Production readiness (before the deploy)

- [ ] Change archived (`openspec archive`), committed, pushed, merged to `main`.
- [ ] **Razorpay dashboard - webhook alert email.** Settings -> Webhooks -> the
      `https://agreementmitra.com/api/webhooks/razorpay` entry: an alert email address is set, and
      it is a mailbox someone reads. A webhook that fails for 24 hours is disabled and this email
      is the only notice. Do this in the mode production uses (test or live).
- [ ] **Production database - message locale.** `SHOW lc_messages;` is `en_US.UTF-8` or `C`.
      The duplicate check reads the constraint name out of the English error message; any other
      locale turns true duplicates into `500`s.
- [ ] **Production database - schema.** Latest `flyway_schema_history` version noted. If it is
      below 28, this deploy applies V28; the deploy's own checks and dump cover that.
- [ ] **Production database - index names** (after V28 is applied, so possibly post-deploy):
      `select indexname from pg_indexes where indexname in
      ('uq_agreement_payment_reference','uq_payment_order_provider_payment_id');` returns both.
- [ ] `DB_URL` on the box still ends `?logServerErrorDetail=false` (the deploy refuses otherwise;
      it is what keeps row content out of Hibernate's own error line).
- [ ] If `91fd214` is not deployed yet: `STAFF_ALERT_DISCORD_WEBHOOK_URL` is set on the box
      (`./provision.sh secrets` from `deploy/`).
- [ ] Baseline taken, to compare after: count of outstanding orders older than a day whose
      agreement is unpaid -
      `select count(*) from payment_order o join agreement a on a.id = o.agreement_id
       where o.status = 'CREATED' and o.created_at < now() - interval '1 day'
       and a.payment_state = 'UNPAID';`

## 3. Deployment

Per `docs/DEPLOYING.md`. SSH to the box, start `tmux`, then:

```sh
cd /opt/agreementmitra && git fetch origin
git show origin/main:deploy/deploy.sh | bash -s -- --dry-run <commit>
git show origin/main:deploy/deploy.sh | bash -s -- <commit>
```

- [ ] Dry run clean.
- [ ] Deploy finished; commit recorded in `/var/lib/agreementmitra/deploys.log`.

**Rollback:** `deploy/deploy.sh rollback`. This change has no migration, so it never blocks a
rollback by itself. V28, if this deploy applied it, can: the script refuses and the printed dump
is then a manual decision.

## 4. Post-deploy validation

- [ ] `deploy/smoke-prod.sh` from the laptop - all PASS (it already checks that the webhook routes
      reach the app).
- [ ] One real agreement generated in the browser.
- [ ] Unsigned webhook from the laptop is rejected:
      `curl -s -o /dev/null -w '%{http_code}\n' -X POST https://agreementmitra.com/api/webhooks/razorpay -H 'Content-Type: application/json' -d '{}'`
      prints `401`.
- [ ] No recording failures since the restart (on the box):
      `docker compose -f deploy/docker-compose.prod.yml logs backend --since 30m | grep -c 'Payment recording failed'`
      prints `0`.
- [ ] Index names present (the query in section 2), if not confirmed before.
- [ ] Razorpay dashboard -> Webhooks -> recent deliveries: no failed deliveries after the deploy
      time. If a payment is made after the deploy, its delivery shows `202`.
- [ ] One payment through production checkout in the mode production uses, ending `PAID`, with a
      `202` delivery in the Razorpay dashboard. (Skip only if the deploy window has no way to make
      one; note that it was skipped.)
- [ ] The outstanding-orders count from section 2 has not grown for a reason other than normal
      abandoned checkouts.

## If `Payment recording failed` appears in production

It means a customer paid and the payment was not recorded. Nothing tells staff yet (register row
`payment-recording-failure-staff-alert`), so the log line and the Razorpay failure email are the
only signals.

1. The line gives the order's last four characters, the refused rule and the SQL state. Find the
   order: `select id, agreement_id, status, created_at from payment_order
   where provider_order_id like '%<last4>';`
2. Confirm in the Razorpay dashboard that the payment was captured.
3. Unblock the customer now: record the payment by hand in the staff console (reference = the
   gateway payment id), or waive it.
4. Fix the cause and deploy. Until then every redelivery and every five-minute reconciliation
   sweep logs the same line for that order.
5. If Razorpay disabled the webhook (after 24 hours of failures): re-enable it in the dashboard
   after the fix is deployed. Events missed while it was off are replayed only by a support
   ticket, per event, within 15 days.
