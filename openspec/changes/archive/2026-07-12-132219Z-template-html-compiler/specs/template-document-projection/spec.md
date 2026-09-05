## ADDED Requirements

### Requirement: Compile an effective template plus user data into escaped HTML

The system SHALL provide a `TemplateCompiler` that maps an **effective template** plus a **user data
map** into a self-contained HTML document. The compiler SHALL render the effective template's sections
and clauses as **system-owned markup**, and SHALL fill each clause's declared `{{slot}}` with the
corresponding value from the data map **HTML-escaped**, so a user value containing markup renders as
literal text and never as document structure or active content. A slot whose value is missing or blank
SHALL render an **escaped placeholder** (never a bare `null`, never unescaped). For every clause
carrying a `showWhen` condition, the compiler SHALL evaluate that condition against the user data map
through the resolution engine's sandboxed boolean DSL evaluator and SHALL **include the clause only
when the condition is true**, dropping it (and closing up its numbering) otherwise. The compiler SHALL
NOT evaluate any condition through Thymeleaf, SpringEL, or any general expression engine. The composed
HTML SHALL be self-contained (fonts by family name, no external URLs).

#### Scenario: Slots are filled with escaped user data

- **WHEN** the compiler renders a clause with a `{{slot}}` bound to a field whose submitted value
  contains angle-bracket markup (for example a script tag)
- **THEN** the composed HTML shows that value as literal text in the clause and does not interpret it
  as markup or active content

#### Scenario: A missing slot renders an escaped placeholder

- **WHEN** the compiler renders a clause with a `{{slot}}` whose field has no value in the data map
- **THEN** the composed HTML shows an escaped placeholder for that slot rather than a blank or a
  literal `null`

#### Scenario: A true showWhen includes its clause

- **WHEN** a clause declares a `showWhen` that evaluates to true against the submitted data
- **THEN** the composed document contains that clause

#### Scenario: A false showWhen drops its clause

- **WHEN** a clause declares a `showWhen` that evaluates to false against the submitted data
- **THEN** the composed document omits that clause and the surrounding clause numbering closes up

#### Scenario: Conditions run only through the sandboxed DSL

- **WHEN** any `showWhen` is evaluated during compilation
- **THEN** it is evaluated by the resolution engine's hand-written boolean DSL over declared fields and
  literals only, with no method call, property navigation, indexing, or expression-engine evaluation

### Requirement: Validate and coerce submitted data against the effective field schema

The system SHALL provide a `SubmittedDataValidator` that validates a submitted data map against the
**effective template's field schema** and returns a **coerced** value map for the compiler. Validation
SHALL check each present value against its field `type` and its declared `FieldValidation` (numeric
`min`/`max`, text `minLength`/`maxLength`/`pattern`, and `enum` membership), and SHALL coerce each
value to the operand type the resolver validated. A declared default SHALL fill an absent field. In
**preview** mode the validator SHALL validate present values, tolerate missing values, and SHALL NOT
enforce `required`; in **generate** mode it SHALL enforce that every `required` field is
present-or-defaulted and valid. On failure the validator SHALL raise a structured validation error
naming offending field **keys** and **rule tokens** only -- it SHALL NOT carry any submitted data
value -- and no document SHALL be compiled from invalid data. The structured validation error SHALL map
to the application's RFC 9457 error contract (`application/problem+json`) with an `errors[]` list of
field-key + rule entries.

#### Scenario: An out-of-bounds present value is rejected

- **WHEN** a submitted value violates its field's declared bounds (for example a `money` field below
  its `min`, or an `enum` value not in `options`)
- **THEN** validation fails, no document is compiled, and the raised error names the field key and rule
  token but contains no submitted data value

#### Scenario: Preview tolerates missing fields

- **WHEN** the validator runs in preview mode with some fields absent but all present values valid
- **THEN** it succeeds without enforcing `required`, leaving the absent fields for the compiler to
  render as placeholders

#### Scenario: Generate enforces required fields

- **WHEN** the validator runs in generate mode and a `required` field is missing (and has no default)
  or invalid
- **THEN** it fails and names the offending field key + rule token, and no document is compiled

#### Scenario: The RFC 9457 body never echoes a data value

- **WHEN** the structured validation error is mapped to an HTTP response
- **THEN** the `application/problem+json` body lists offending field keys and rule tokens in `errors[]`
  and contains no submitted data value

### Requirement: The compiler and validators never log rendered content or submitted values

The compiler and validators SHALL uphold the never-log invariant: they SHALL NOT write the composed
HTML or any submitted data value to any log at any level. Validation errors SHALL cite field keys /
rule tokens only.

#### Scenario: Compiling and validating leave no PII in logs

- **WHEN** a document is compiled and its data validated
- **THEN** no log line contains the composed HTML or a submitted data value
