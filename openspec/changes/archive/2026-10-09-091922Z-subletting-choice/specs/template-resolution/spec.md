## MODIFIED Requirements

### Requirement: Sandboxed showWhen DSL

The system SHALL define `showWhen` as a **closed boolean grammar** over declared field keys and
number/string/bool literals, supporting comparison (`== != < <= > >=`), boolean (`&& || !`), and
parentheses -- and **nothing else**: no method calls, property navigation, indexing, function calls,
or assignment. The system SHALL parse `showWhen` with a hand-written parser (NOT an expression
library and NOT Thymeleaf SpringEL). As part of resolution the system SHALL **validate** that every
`showWhen` in the effective template parses, references only declared field keys, and compares an
`enum` field with a string literal only when that literal is one of the field's declared options -- a
literal matching no option would otherwise drop its clause silently at render. The system SHALL
provide a **pure evaluator** `evaluate(expr, fieldValues) -> boolean` that reads only the supplied
field-value map; this evaluator SHALL NOT be wired to any render in this capability.

#### Scenario: A valid condition parses and references declared fields

- **WHEN** a clause declares `showWhen: "escalationPct > 0 && furnished == true"` and both identifiers
  are declared fields
- **THEN** resolution parses it and accepts it

#### Scenario: A condition referencing an undeclared field fails resolution

- **WHEN** a `showWhen` references an identifier that is not a declared field key
- **THEN** resolution raises `ResolutionException` naming the clause and the unknown identifier

#### Scenario: An enum compared with a literal that is not one of its options fails resolution

- **WHEN** a `showWhen` compares an `enum` field with a string literal that is not among the field's
  declared options (for example `mode == "Courts"` where the options are `courts` and `arbitration`)
- **THEN** resolution raises `ResolutionException` naming the clause, the field and the literal

#### Scenario: The grammar admits no code or property access

- **WHEN** a `showWhen` contains a method call, property dereference, index, function call, or
  assignment
- **THEN** the parser rejects it as a syntax error and resolution fails

#### Scenario: The evaluator is a pure function of the value map

- **WHEN** the evaluator is given a parsed condition and a field-value map
- **THEN** it returns the boolean result reading only that map, with no side effect, IO, or template
  access
