## ADDED Requirements

### Requirement: Agreement ids are redacted in logs, exception messages and named toString output
The backend SHALL never write a raw agreement id to an application log line or to the message of an exception it constructs, nor to the `toString()` of `Agreement`, `SigningCompletionView`, `StaffAgreementView`, `StampInfo`, `StampQueueEntry`, `PaymentConfirmation`, `PaymentConfirmedEvent`, `RazorpayClient.ProviderOrder` or `SignRequest`; it SHALL render the id instead as its first 8 hexadecimal characters followed by `…`. The agreement id is a bearer capability (holding it grants anonymous read, draft upload, finalise, contact edit and payment), so a raw id in a log is a working credential in the hands of anyone with log access. The 8-character prefix leaves 90 of the UUID's 122 random bits unknown; an operator finds the row with a prefix match, which can return more than one row and is disambiguated by timestamp or tracking reference. The rule applies to an agreement id embedded in a larger string as well — an object-storage key such as `drafts/<agreementId>.pdf` is logged and put into exception messages in redacted form. The named `toString()` types are those with a hand-written override plus the internal records that carry the id; response DTO records serialised to JSON and never logged are outside this requirement. Ids that are not capabilities (signing-request, delivery, identity, provider ids, tracking references) are outside it too; provider ids keep their existing last-4 redaction.

#### Scenario: An id-bearing log statement prints the redacted form
- **GIVEN** an agreement whose id is `1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d`
- **WHEN** the backend logs an event about that agreement at any level (draft stored, order placed, payment recorded or waived, e-Stamp attached, signing request created, signing window extended, invitations re-sent, stamping fallback, closed as completed or abandoned)
- **THEN** the log line contains `1a2b3c4d…`
- **AND** it does not contain the full id

#### Scenario: INFO-level closure and fallback lines are redacted
- **GIVEN** the `in.agreementmitra` logger at its production default `INFO`
- **WHEN** an agreement closes as completed or as abandoned, or is stamped onto its stored draft without a re-render
- **THEN** the emitted INFO line contains the redacted id and not the full id

#### Scenario: A stored-object log line redacts the id inside the key
- **GIVEN** a draft PDF stored under the key `drafts/1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.pdf`
- **WHEN** the blob store logs the write
- **THEN** the line shows `drafts/1a2b3c4d….pdf`
- **AND** a failed read or write raises an exception whose own message shows the same redacted key

#### Scenario: A constructed exception message carries no raw id
- **GIVEN** a lookup for an agreement id that does not exist, an agreement with no signed document, or an agreement that vanishes between two reads
- **WHEN** the signing module throws `ResourceNotFoundException` or `IllegalStateException` for it
- **THEN** the exception message contains the 8-character redacted id and not the full id
- **AND** the HTTP problem detail is unchanged (it never carried the message)

#### Scenario: A unique-constraint violation does not log the key value
- **GIVEN** the shipped default datasource URL
- **WHEN** an insert violates a unique constraint keyed on the agreement id (e.g. two racing checkout starts)
- **THEN** the URL carries `logServerErrorDetail=false`, so the driver message Hibernate logs at ERROR omits the `Key (agreement_id)=(…)` detail

#### Scenario: Named toString output redacts the id
- **WHEN** `toString()` is called on any of the named types carrying an agreement id or an id-bearing blob key
- **THEN** the output shows only the redacted form

#### Scenario: A full lifecycle at DEBUG leaks no agreement id
- **GIVEN** the `in.agreementmitra` logger is set to `DEBUG` and every log event reaching the root logger is captured
- **WHEN** an agreement is created, given a draft, finalised (order placed and signing request created), has its payment waived by staff, receives its e-Stamp, and has eSign initiated
- **THEN** the capture includes the draft-stored, stored-object and signing-request-created lines, each showing the redacted id
- **AND** the signing request reached `SIGN_REQUESTED`
- **AND** no captured formatted message, nor any message in a captured throwable's cause chain, contains the agreement's full id

#### Scenario: A new raw agreement id in a log call or exception message fails the build
- **GIVEN** a log statement, or a `new …Exception(…)` / `new …Error(…)` construction, in `backend/src/main/java` whose arguments include an agreement-id expression (`agreementId`, `.agreementId()`, `agreement.id()`, `agreement.getId()`) not wrapped in `AgreementIds.redact` or `AgreementIds.redactIn`
- **WHEN** the unit test suite runs
- **THEN** a source-scan test fails and names the file and line

### Requirement: Application logging defaults to INFO outside the local profile
The backend SHALL default the `in.agreementmitra` logger to `INFO`, and only the `local` profile SHALL raise it to `DEBUG`; an operator MAY still override it with `LOGGING_LEVEL_IN_AGREEMENTMITRA`. DEBUG output on identity/legal infrastructure is opt-in per deployment, never the shipped default. Tests that assert the absence of sensitive values in DEBUG lines SHALL pin their logger to `DEBUG` themselves, so the default change cannot turn them into assertions over an empty capture.

#### Scenario: A deployment without an override logs at INFO
- **GIVEN** the backend's shipped configuration with a profile other than `local` and no `LOGGING_LEVEL_IN_AGREEMENTMITRA`
- **WHEN** the configured level of the `in.agreementmitra` logger is read
- **THEN** it is `INFO`

#### Scenario: The local profile keeps DEBUG
- **GIVEN** the `local` profile's configuration
- **WHEN** the configured level of the `in.agreementmitra` logger is read
- **THEN** it is `DEBUG`

#### Scenario: Existing DEBUG redaction tests still observe the DEBUG lines
- **GIVEN** the Razorpay webhook, Leegality and Zoop redaction tests that assert sensitive values are absent from DEBUG log lines
- **WHEN** they run under the new `INFO` default
- **THEN** each pins its logger to `DEBUG` for the test and asserts at least one `DEBUG`-level event was captured before asserting absence
