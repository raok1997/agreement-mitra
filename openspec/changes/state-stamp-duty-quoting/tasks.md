## 1. Schema and seed data

- [ ] 1.1 Write the forward-only Flyway migration creating `stamp_duty_rule_set` (jurisdiction,
      `effective_from`, citation, pending-verification marker), `stamp_duty_slab` (property use, term
      range, rate in basis points, base composition, included components, cap, floor, flat-override
      qualifier), and `stamp_denomination` (jurisdiction, minor units)
- [ ] 1.2 Extend the same migration with `state_charge` (jurisdiction, `effective_from`, service fee,
      optional per-template-type service-fee override, procurement fee, delivery fee, tax rate) and
      `understamp_acknowledgement` (agreement id, assessed duty, chosen denomination, shortfall,
      warning version, timestamp)
- [ ] 1.3 Add `duty_jurisdiction` and `stamp_denomination_minor_units` to `agreement`, and the frozen
      breakdown, denomination and jurisdiction columns to `payment_order`
- [ ] 1.4 Seed the Karnataka rule set: Article 30 slabs (0.5% capped at Rs. 500 for residential
      terms of one year or less, 0.5% uncapped for commercial/industrial, 1% / 2% / 3% for the longer
      term bands, conveyance treatment above thirty years), the family-lease flat overrides, the
      `AVERAGE_ANNUAL_RENT` base including money advanced, the one-year registration threshold at 2%,
      each row carrying its citation and pending-verification marker
- [ ] 1.5 Seed the Telangana rule set: 0.4% of `TOTAL_TERM_RENT` plus advance, uncapped, registration
      optional below twelve months and compulsory at twelve months and above at 0.2%, with citations
      and pending-verification markers
- [ ] 1.6 Seed the denomination master and `state_charge` rows for both jurisdictions
- [ ] 1.7 Confirm `ddl-auto: validate` still passes against the new schema with no entity drift

## 2. Rules module: duty assessment

- [ ] 2.1 Define the public module API in `in.agreementmitra.rules`: `StampDutyRules`,
      `DutyRequest` (jurisdiction, property use, term months, monthly rent, security deposit,
      premium, fine, special-case qualifier -- and deliberately no party fields), `DutyAssessment`,
      `PropertyUse`; keep everything else package-private
- [ ] 2.2 Implement the configuration entities and repositories for the rule set, slabs and
      denomination master, with the shared `effective_from` resolution helper
- [ ] 2.3 Implement load-time validation that rejects overlapping term ranges, gaps between term
      ranges, unknown base compositions, and a national pseudo-jurisdiction, with a diagnostic naming
      the jurisdiction and offending slab
- [ ] 2.4 Implement slab matching and the duty calculation: base composition, included components,
      rate in basis points, cap and floor, flat-rate overrides, `BigDecimal` arithmetic rounded up to
      the whole rupee, returned as integer minor units with an explicit currency
- [ ] 2.5 Implement the basis string (rate, base components, cap applied, citation) and the
      registration-compulsory determination
- [ ] 2.6 Implement denomination lookup: return the jurisdiction's denominations and identify the
      lowest at or above the assessed duty, reporting explicitly when none reaches it
- [ ] 2.7 Implement the unsupported-jurisdiction result: no duty, no denominations, no fallback rate

## 3. Rules module: tests

- [ ] 3.1 **Unit tests** for the Karnataka calculation across every slab and boundary: the Rs. 500 cap
      applied and not applied, residential capped vs commercial uncapped at identical terms, each term
      band boundary (exactly 12 months, exactly 120, exactly 240, exactly 360), the family-lease flat
      overrides for all three area bands, and money-advanced inclusion in the base
- [ ] 3.2 **Unit tests** for the Telangana calculation: total-term-rent base, deposit inclusion, no
      cap applied at high rent, and the twelve-month registration boundary
- [ ] 3.3 **Unit tests** proving both jurisdictions run through the same code path with no
      state-specific branching, and that identical terms yield each jurisdiction's own duty
- [ ] 3.4 **Unit tests** for load-time validation failures: overlapping ranges, a gap between ranges,
      an unknown base composition, a national jurisdiction
- [ ] 3.5 **Unit tests** for denomination selection: exact match, next-higher match, and no
      denomination reaching the duty
- [ ] 3.6 **Unit tests** for rounding: duty rounded up to the whole rupee, results as integer minor
      units, no floating-point anywhere in the path
- [ ] 3.7 **Integration test** loading the seeded Karnataka and Telangana configuration from a real
      database and asserting end-to-end assessments, including `effective_from` selection across a
      dated rate change
- [ ] 3.8 Confirm `ModularityTests` stays green and no consumer references the rules module internals

## 4. Jurisdiction resolution and stamp selection

- [ ] 4.1 Resolve an agreement's duty jurisdiction from the pinned template's `state` dimension via
      `documents.api.TemplateCatalogApi`, and its property use from the template `type`
- [ ] 4.2 Persist an explicitly chosen property state on the agreement for national templates, with
      no default and no address parsing; refuse a change once a payment order exists
- [ ] 4.3 Add the stamp-selection endpoint: return the assessed duty, its basis, the registration
      position and the jurisdiction's denominations, with the satisfying denomination pre-selected
- [ ] 4.4 Validate a submitted denomination server-side against the jurisdiction's master; reject any
      value absent from it, and ignore any client-supplied amount, total or fee
- [ ] 4.5 Require an explicit acknowledgement for a below-statutory selection; refuse the selection
      without one, and persist the immutable acknowledgement audit record with no party data
- [ ] 4.6 Refuse a selection change once a payment order exists for the agreement

## 5. Quoting and payment

- [ ] 5.1 Replace `PaymentPricing.priceFor` with `quoteFor(UUID) -> Quote`: an ordered list of typed
      line items (`STAMP_DUTY`, `SERVICE_FEE`, `PROCUREMENT_FEE`, `DELIVERY_FEE`, `TAX`) whose total
      is the exact sum of its parts
- [ ] 5.2 Compose the line items from the agreement's selected denomination and the jurisdiction's
      `state_charge` row, including the per-template-type service-fee override
- [ ] 5.3 Compute tax on the service, procurement and delivery items only, excluding the stamp duty
      pass-through, at the jurisdiction's configured rate
- [ ] 5.4 Round each line item once to whole paise (`HALF_UP`) and sum the rounded items; assert no
      independently authored total exists
- [ ] 5.5 Freeze the breakdown, denomination and jurisdiction onto `payment_order` at creation, and
      return the frozen record on every subsequent read rather than recomputing
- [ ] 5.6 Refuse order creation when the jurisdiction is unsupported or unresolved, or when no
      denomination is recorded, substituting no default amount
- [ ] 5.7 Extend `CheckoutSessionResponse` with the line-item breakdown, marking the stamp duty line
      as a statutory pass-through; introduce no client-supplied amount

## 6. Stamp intake reconciliation

- [ ] 6.1 Reject an uploaded certificate whose duty amount is below the agreement's frozen
      denomination with a `400` and a field-level error naming the required amount, before any blob is
      written or state changed; accept one at or above it
- [ ] 6.2 Reject an uploaded certificate whose jurisdiction does not match the agreement's duty
      jurisdiction, with a field-level error
- [ ] 6.3 Confirm a refused upload leaves the signing request in `PDF_GENERATED` and does not
      transition to `STAMP_FAILED`
- [ ] 6.4 Add the frozen paid-for denomination to `StampQueueEntry`, sourced from the order record and
      never recomputed; leave rent, deposit, contacts and full address excluded

## 7. Payment, selection and intake tests

- [ ] 7.1 **Unit tests** for quote composition: line-item types present, tax base excludes duty, total
      equals the sum of rounded items, per-template-type service-fee override applied, delivery zero
      where configured
- [ ] 7.2 **Unit tests** for selection validation: a denomination outside the master rejected, a
      client-supplied amount ignored, an unacknowledged below-statutory selection refused, an
      acknowledged one accepted, an above-statutory selection accepted without a warning
- [ ] 7.3 **Unit tests** for intake reconciliation: below-denomination rejected, at-denomination
      accepted, above-denomination accepted, jurisdiction mismatch rejected
- [ ] 7.4 **Integration test** of the full quote-to-order flow against a real database: select a
      denomination, create an order, assert the frozen breakdown, then change the jurisdiction's
      configured charges and assert the placed order's amount and breakdown are unchanged
- [ ] 7.5 **Integration test** that an agreement with an unsupported or unresolved jurisdiction, or no
      selected denomination, cannot create an order
- [ ] 7.6 **Integration test** of intake reconciliation end to end: a certificate below the paid-for
      denomination is refused and the request stays awaiting a stamp; a correct one attaches and
      transitions to `STAMPED`
- [ ] 7.7 **Integration test** that the staff queue entry carries the frozen denomination and still
      excludes rent, deposit, contacts and the full address

## 8. Frontend

- [ ] 8.1 Add the stamp-selection step: assessed duty, its basis, the registration position, and the
      state's denominations as a bounded choice with the satisfying one pre-selected
- [ ] 8.2 Add the property-state choice shown only for national templates, before any duty is
      displayed, with no default selected
- [ ] 8.3 Add the under-stamp warning and its explicit acknowledgement control -- not default-checked,
      not dismissible as an acknowledgement
- [ ] 8.4 Show the itemised quote at checkout with the stamp duty identified as a statutory
      pass-through
- [ ] 8.5 **Component tests** for the denomination selector, the acknowledgement gate, and the
      breakdown display

## 9. Security, verification and docs

- [ ] 9.1 Verify no party name, contact, address or identity value reaches the rules module, the
      acknowledgement record, or any log line on the quoting, selection or intake paths
- [ ] 9.2 Run `./run-tests.sh` and confirm `ModularityTests`, the coverage gate, and the security
      scans pass
- [ ] 9.3 Record the pending-verification markers as a blocking pre-production checklist item, and
      note the GST pure-agent treatment as awaiting the client's CA sign-off
- [ ] 9.4 Update `docs/integrations/leegality.md`, whose unit economics assume a flat Rs. 100 duty, to
      reference the real per-state assessment
