# Flow Journal -- Owned Agreements: Save / Resume / Edit (CR-B)

> **Purpose.** Development-kickoff handoff for the **ownership layer** -- the second of two CRs for
> optional Google login. It walks the owned-agreement journey, freezes the contracts, and lists
> watch-outs. Rationale is in `design.md` (D1..D8); normative behavior is in the `specs/` deltas.
>
> **Depends on CR-A (`google-oauth-login`).** CR-A must be applied first -- this CR consumes its
> authenticated **identity-id principal** and its `identity` table (the `owner_identity_id` FK).

Locked decisions: **full field edit** of in-progress agreements (gated on the existing draft-freeze) and
**optional login** (anonymous drafting untouched; ownership is additive).

---

## 1. The owned-agreement journey (starts after login)

```
  User (logged in via CR-A)     Frontend (Vue)                  Backend (signing/agreement)
   | drafted anonymously ------>| {id} in hand, owner = NULL     |
   | click "Save" ------------->| POST /api/agreements/{id}/claim ->| set-if-unowned: owner = me
   |                            |   200 <-----------------------|   (404 if unknown / not-yours)
   |                            |                               |
   | come back later ---------->| GET /api/agreements --------->| findByOwnerIdentityId, most-recent
   |                            |   [ {id,status,editable} ] <--|   + derived status projection
   |  see in-progress + signed  |                               |
   |                            |                               |
   | reopen an in-progress one->| GET /api/agreements/{id} ---->| owner-scoped read (404 to non-owner)
   | edit + submit ------------>| PUT /api/agreements/{id} ---->| revalidate, replace terms+parties,
   |                            |   200 <-----------------------|   clear pinned draft  [pre-sign only]
   |                            |                               |
   | send for signing (existing)|  -------------------------->  | signing request created -> FROZEN
   |                            |                               |   (edit now 409; status IN_PROGRESS)
   | later ------------------->| GET /api/agreements --------->| status SIGNED, editable=false ->
   |                            |   View/Download <-------------|   read-only
```

**Invariants:**
- Ownership is **server-sourced** at claim; never a body field (D1, D2). Claim is atomic set-if-unowned.
- The list shows **both in-progress and signed**; `status` is a **read-only projection** of the signing
  FSM, never stored on the agreement (D3).
- Edit is allowed **only while no signing request exists** -- the same freeze the draft obeys; `409` after
  (D4). `editable` in the list == the same predicate, so the button and the backend can't disagree.
- Claim / owner-scoped read / non-owner edit all return **`404`, never `403`** -- no ownership oracle
  (D2, D4, D5).
- Anonymous create + capability read stay **open**; only save / list / edit are gated (D6).

---

## 2. Status projection -- "in-progress vs signed" (D3)

The `agreement` stays status-less. The summary derives status by asking the signing side via the
`signing.SigningRequestQuery` seam (extended to return a `SignatureStatus` per agreement, or none):

```
  no signing_request              -> DRAFT         editable = true
  PDF_GENERATED | STAMPED |
  SIGN_REQUESTED                  -> IN_PROGRESS   editable = false (frozen)
  SIGNED                          -> SIGNED        read-only, downloadable
  EXPIRED                         -> EXPIRED
  FAILED | STAMP_FAILED           -> ACTION_NEEDED
```

`editable` is exactly "owner AND no signing request exists" -- the predicate
`SigningRequestQuery.existsForAgreement` that `DraftService` already uses for the draft freeze. Reuse it for
both the list flag and the `PUT` gate so they can never diverge.

---

## 3. Frozen contracts

**Agreement endpoints (signing.api):**
- `POST /api/agreements`                 (anon)  -> unchanged; owner NULL
- `GET  /api/agreements/{id}`            (open)  -> capability read while unowned; owner-scoped once claimed
                                                    (404 for non-owner)
- `POST /api/agreements/{id}/claim`      (auth)  -> set-if-unowned; idempotent; 404 = unknown/other
- `GET  /api/agreements`                 (auth)  -> `[ AgreementSummaryResponse ]`, most-recent first
- `PUT  /api/agreements/{id}`            (auth)  -> full edit; 409 if signing requested; 404 if not
                                                    owner/unknown; body = create shape minus server fields

**AgreementSummaryResponse** = `{ id, propertyAddress, monthlyRent, startDate, endDate, duration,
createdAt, status, editable }` where `status in {DRAFT, IN_PROGRESS, SIGNED, EXPIRED, ACTION_NEEDED}`.

**Consumed from CR-A:** the authenticated **identity-id (UUID) principal** from the Security context. That
is the only value that crosses the `signing <-> identity` boundary -- no `identity` type is imported by
`signing` (D1); `ModularityTests` proves it.

**Schema (V12):** `agreement.owner_identity_id uuid` (nullable FK to CR-A's `identity`, indexed). Forward-
only; never edit V1..V11.

---

## 4. Independently-startable slices

1. **Schema** (`tasks.md` 1) -- V12 column. Trivial; unblocks the rest.
2. **Ownership backend** (`tasks.md` 2, 3) -- `owner_identity_id`, claim, list-mine with derived status,
   edit, read-scoping, SecurityConfig matchers. Buildable against a **fake principal** (a test double for
   CR-A's filter) so it does not block on CR-A's internals -- only its principal contract.
3. **Frontend** (`tasks.md` 4) -- My Agreements, Save, Edit. Depends on the frozen contracts + CR-A's auth
   store.

---

## 5. Watch-outs

- **Matcher order** -- the authenticated `GET /api/agreements`, `.../claim`, and `PUT /api/agreements/*`
  matchers MUST precede the broad `/api/agreements/*` permits, or the capability read / anonymous create
  will shadow them (D6; test 5.8).
- **Anti-mass-assignment** -- owner and id come from the session/server only; a body `ownerIdentityId` or
  `id` must be ignored on both claim and edit (spec; test 5.7).
- **No oracle** -- claim, owner-scoped read, and non-owner edit all return `404`, never `403` (D2, D4, D5).
  Do not add a "not yours" message.
- **Edit clears the pinned draft** -- after a `PUT`, `draft_pdf_key` + the template pin are cleared so the
  next generate re-renders from the new terms; never serve a stale draft (D4).
- **Read-scoping is in the handler, not the chain** -- the filter chain can't see the row's owner, so
  `GET /{id}` stays `permitAll` and the handler enforces owner-vs-capability (D5, D6).
- **Ordering dependency** -- `V12` and the principal need CR-A applied first (FK to `identity`); a deploy
  skipping CR-A fails fast (D7).
- **Windows dev** -- container tests with `TESTCONTAINERS_RYUK_DISABLED=true`; backend with
  `-Duser.timezone=Asia/Kolkata` (repo memory).

---

## 6. Definition of done

- All `tasks.md` boxes checked; `ModularityTests`, `./gradlew check` (incl. `securityScan`), and
  `npm run security:scan` green.
- Live-driven once end-to-end (with CR-A applied): anonymous draft -> Sign in with Google -> Save -> My
  Agreements shows it -> Edit an in-progress one -> a signed one is read-only; non-owner claim/read/edit
  return `404`.
- `openspec validate` clean; ready for `/opsx:apply` then `/opsx:archive`.
