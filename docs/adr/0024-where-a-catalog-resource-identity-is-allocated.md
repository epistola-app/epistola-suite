# ADR 0024: Where a catalog resource identity is allocated

- **Status:** Proposed
- **Date:** 2026-09-17
- **Deciders:** Epistola team
- **Tags:** catalog, identity, database, relocation
- **Related:** [ADR 0014](0014-safe-catalog-resource-relocation.md) (identity separated from address),
  [ADR 0020](0020-where-a-catalog-resource-address-lives.md) (the address and the trigger that syncs it),
  [ADR 0025](0025-relocation-without-aliases.md) (aliases withdrawn; the registry and trigger stay),
  [#952](https://github.com/epistola-app/epistola-suite/issues/952) (the refactor)

## Context

ADR 0014 gave every catalog resource a stable, tenant-local `resource_id`, held in the
`catalog_resources` registry and never exported. A catalog's wire format addresses a resource by
`(type, catalog, key)`, so an import always has to turn an address into an identity: reuse the one
registered there, or allocate one. Two concurrent imports of the same resource must end up with one
identity, so that step has to be atomic.

No decision recorded **who allocates** it. The relocation work (#869) put it in
`sync_catalog_resource_identity`, the `BEFORE INSERT` trigger on the seven resource tables. What the
trigger does depends on the incoming row:

- a NULL `resource_id` reuses the identity registered at the row's address, or mints a new one;
- a non-NULL `resource_id` must match what is registered there, or the insert raises;
- either way, the trigger then writes the registry row.

The trigger's `UPDATE` and `DELETE` branches keep the registry's copy of the address in step, which
is ADR 0020's subject and not this one's.

Everything that writes a resource leans on that trigger without saying so:

- **Create and import paths.** None of the 12 inserts into `assets`, `code_lists`, `fonts`,
  `variant_attribute_definitions`, `themes`, `stencils` and `document_templates` names
  `resource_id`. Six of them are imports that upsert on the address (`ON CONFLICT` on
  `(tenant_key, catalog_key, key)`), and they depend on NULL meaning "reuse".
- **Snapshot restore.** `RestoreTenantSnapshot` plants the recorded identities in the registry first,
  then runs the ordinary imports, which reuse those identities by address.
- **Faithful tenant-backup restore** (`epistola-support-backups`) restores `catalog_resources` itself
  and writes every column, `resource_id` included, so it already takes the explicit path.
- **The upgrade backfill** in `V20260905090000__core_catalog_resource_identity.sql` mints an identity
  for every existing resource in SQL.

The question came up when the trigger turned out to require PostgreSQL 18: it minted with 18's
builtin `uuidv7()`. That was replaced by `epistola_uuidv7()`, a pure-SQL UUIDv7 used on every
server version, before the requirement shipped. Which raises the question of whether allocation
belongs in the database at all, given that every other identifier in the application (documents,
generation requests, logs, users) is generated in Kotlin by `UUIDv7.generate()`.

**Performance does not decide it.** Measured on PostgreSQL 17.9 and 18.3 in Podman on macOS, so
rough:

| Generator                    | Per call | Per insert through a trigger shaped like the real one | Registry primary key, 100k rows |
| ---------------------------- | -------- | ----------------------------------------------------- | ------------------------------- |
| `gen_random_uuid()` (random) | 0.8 µs   | 26–27 µs                                              | 4.2 MB                          |
| builtin `uuidv7()` (18 only) | 0.8 µs   | 24.8 µs                                               | 3.0 MB                          |
| `epistola_uuidv7()` (SQL)    | 4.8 µs   | 30–31 µs                                              | 3.0 MB                          |
| same body in PL/pgSQL        | 2.4 µs   | 27–29 µs                                              | 3.0 MB                          |

Identities are allocated only when a catalog resource is created, imported or restored, so the
difference is noise. Any UUIDv7 keeps the index compact, whichever implementation generates it.

## Considered options

### A. The trigger reuses or mints (what the code does now)

**Pros:** every writer gets an identity without doing anything, including raw SQL in migrations and
fixtures. Reuse by address is atomic inside one statement. Restore and imports share one path.

**Cons:**

- **Invisible.** Nothing in `ImportTheme` says that identity is decided there.
- **One column, two meanings.** Whether `resource_id` is NULL silently switches the trigger between
  "reuse or mint" and "must match or fail". The migration needs a paragraph of comments to explain
  that.
- **Tied to the database server.** How identities are generated depends on the server version, which
  is how PostgreSQL 18 briefly became a requirement on `main`.
- **Against the house convention.** It is the one identifier the application does not generate.

### B. The application proposes, the registry decides (proposed)

One operation, owned by `catalog/identity/`, registers an address with an application-proposed
identity and returns whichever identity the address ends up with:

```sql
INSERT INTO catalog_resources (tenant_key, resource_id, resource_type, catalog_key, resource_key)
VALUES (:tenant, :proposedId, :type, :catalog, :key)
ON CONFLICT (tenant_key, resource_type, catalog_key, resource_key)
    DO UPDATE SET resource_key = EXCLUDED.resource_key   -- no-op, so RETURNING yields the existing row
RETURNING resource_id
```

The writer then inserts the domain row with the returned `resource_id`. Checked on PostgreSQL 17:

- a new address keeps the proposed identity;
- an existing address returns the identity already registered there;
- a concurrent second writer blocks until the first commits, then receives the first writer's
  identity, leaving one registry row.

`ON CONFLICT DO NOTHING` does not work: it returns no row on conflict. A follow-up `SELECT` in the
same statement would not see a row that a concurrent transaction committed after the statement
began.

**Pros:**

- **Explicit.** Every writer visibly registers, and restore states its intent with a mode rather than
  with a non-NULL value.
- **Same generator as everything else.** Identities come from `ResourceIdentity.generate()`, which
  already exists and is unused.
- **No server-version dependency** in how identities are generated.
- **Still atomic.** The registry's address constraint does the arbitration that the trigger's lookup
  does today.
- **Hard to forget.** `resource_id` is already `NOT NULL` with a foreign key to the registry, so a
  writer that skips registration fails on its first insert.

**Cons:**

- **Every writer must call it.** That is 12 insert sites plus snapshot restore to change.
- **One more statement** per resource write.
- **Extra row version on reuse.** Re-registering an existing address writes a new row version for
  the no-op update.
- **Node clocks, not the database clock.** Identities are timestamped by each application node's
  clock. With several nodes, identities minted within the clock-skew window sort arbitrarily among
  themselves, which costs nothing for index locality.
- **Raw SQL has to register explicitly.** A migration or fixture that inserts a resource can no longer
  rely on the trigger.

### C. The application reads, then generates

Look up the address; if nothing is there, generate and insert.

**Rejected:** a read-then-write race. Two concurrent imports both find nothing, both generate, and
the loser fails on the unique constraint instead of converging on the winner's identity.

### D. Split by path

Kotlin allocates on create commands, and the trigger keeps handling upserts and restore.

**Rejected:** two sources of truth. Which side allocated an identity would depend on which command
wrote the row, and the trigger's NULL-means-reuse rule would survive anyway.

## Decision

**Proposed: B.** Identity allocation becomes one explicit reuse-or-register operation, with the
proposal generated by `ResourceIdentity.generate()`. It takes a mode:

- **`ADOPT`** reuses whatever is registered at the address, for creates and imports.
- **`REQUIRE`** is for restore: the result must equal the proposal, otherwise it raises the error
  the trigger raises today.

The trigger loses its `INSERT` branch in a new migration. Its `UPDATE` and `DELETE` branches stay,
per ADR 0020.

`epistola_uuidv7()` stays. The upgrade backfill uses it, and a future migration that has to mint an
identity in SQL can too.

This is proposed rather than accepted: option A is correct, tested and shipping in the next release,
and the refactor is tracked separately in #952.

## Consequences

- **No data migration.** Stored identities are UUIDv7 whichever side generated them, so the move can
  happen in any release, without a schema change beyond dropping the trigger's `INSERT` branch.
- **Converging concurrent imports becomes explicit.**
  `CatalogResourceIdentityIntegrationTest`'s "two transactions cannot register different identities
  at one address" must stay green through the new operation. A test should also cover a skipped
  registration failing on `NOT NULL`.
- **Restore gets a named mode.** `RestoreTenantSnapshot` passes recorded identities with `REQUIRE`
  instead of planting registry rows ahead of the import. The faithful tenant-backup restore already
  writes identities explicitly and should be unaffected; that needs verifying.
- **Raw SQL must register.** Test fixtures that insert resources with raw SQL must register them
  explicitly. The project rules already steer fixtures towards commands.
- **Until it is done**, the trigger remains the only place identities are allocated, and new writers
  should keep leaving `resource_id` NULL rather than start a second convention.

## References

- `V20260905090000__core_catalog_resource_identity.sql`: `epistola_uuidv7()`, the backfill and
  `sync_catalog_resource_identity`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/snapshot/RestoreTenantSnapshot.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/common/ids/EntityKey.kt`:
  `ResourceIdentity.generate()`
- [Catalog resource identity migration](../catalog-resource-identity-migration.md)
