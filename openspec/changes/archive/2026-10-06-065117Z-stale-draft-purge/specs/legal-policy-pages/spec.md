## ADDED Requirements

### Requirement: Policy texts state the unpaid-draft retention period

Terms-of-service §10 (`drafts`) SHALL state that an unpaid draft is deleted, within a few days, once it has gone
90 days without a change to its content, whether or not it is saved to an account, that it is removed as a draft the owner
deletes is removed (so that clause's caveats on backups and emailed copies apply), that the agreement's link
stops working afterwards, and that an agreement finalised for signing or taken to payment is not deleted this
way. It SHALL NOT describe the period as a discretion ("may delete", "a long time").
The privacy policy's `deleted-drafts` clause SHALL be headed "8. Deleted drafts" and SHALL describe the record as naming the account the draft was saved
to, if any, rather than the account that deleted it, and SHALL say that the same record is kept when we delete
an unpaid draft that has gone 90 days without a change. The privacy policy's `retention` gap SHALL name the 90-day
draft period among the periods it states. Neither the terms' `drafts` clause nor the privacy `deleted-drafts`
clause SHALL state a retention period for an agreement that has been finalised or gone to payment. A frontend test SHALL pin the 90-day figure in terms §10, and a backend test
SHALL pin the backend's period at 90 days, so that a change on either side fails a test on that side.

#### Scenario: Terms §10 states the period

- **WHEN** the frontend test suite runs
- **THEN** terms-of-service clause `drafts` contains "90 days" and "finalised", and does not contain "a long
  time" or "may delete"

#### Scenario: The privacy policy covers a draft we delete

- **WHEN** the frontend test suite runs
- **THEN** privacy-policy clause `deleted-drafts` is headed "8. Deleted drafts", mentions a draft we delete after 90 days without a change,
  does not say "the account that deleted it", and still contains no party detail in its description of the
  record
- **AND** privacy-policy clause `retention`'s gap mentions the 90-day draft period

#### Scenario: The backend period matches the terms

- **WHEN** the backend unit tests run
- **THEN** the retention period constant equals 90 days
