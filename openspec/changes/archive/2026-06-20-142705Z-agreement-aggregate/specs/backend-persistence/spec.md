## REMOVED Requirements

### Requirement: Schema validation stays green with no entities
**Reason**: This requirement was explicitly transitional — true only while the codebase had zero
JPA entities. The `agreement-aggregate` change introduces the first mapped entity (`Agreement`)
and its `V2__agreement.sql` migration, so its premise (an empty schema with no mapped entities) no
longer holds.

**Migration**: The durable guarantee — that `ddl-auto: validate` passes against real mapped
entities and their Flyway migrations, with Flyway running before JPA initializes — is now carried
by the `agreement` capability's "Schema matches the agreement entity mapping" requirement, and is
re-asserted by each future entity-bearing change against its own migration. The general Flyway
posture (migrations apply on startup, JPA validates and never generates, destructive clean
disabled) remains in this capability's "Flyway-managed versioned schema" requirement, unchanged.
