# Database migrations

Epistola Suite uses [Flyway](https://flywaydb.org/) for schema migrations against
PostgreSQL. This document is the source of truth for **where migrations live**,
**how they are versioned**, and **how to add one**.

## Ownership: migrations live in the module that owns the tables

Migrations are **module-owned**. A module that owns a set of tables also owns
their migrations, under:

```
<module>/src/main/resources/db/migration/<module>/VYYYYMMDDHHMMSS__<module>_<desc>.sql
```

| Module                  | Subfolder                | Owns                                                                       |
| ----------------------- | ------------------------ | -------------------------------------------------------------------------- |
| `modules/epistola-core` | `db/migration/core/`     | Everything except the tables below (tenants, templates, documents, …)      |
| `modules/feedback`      | `db/migration/feedback/` | `feedback`, `feedback_comments`, `feedback_assets`, `feedback_sync_config` |
| `modules/loadtest`      | `db/migration/loadtest/` | `load_test_runs`                                                           |

There is one **global Flyway namespace**: at application runtime every module's
`src/main/resources` is merged onto one classpath, so `apps/epistola` sees all
migrations under `classpath:db/migration` (Flyway scans subdirectories
recursively — the per-module subfolder is organisational only, no
`spring.flyway.locations` change is needed). Because the namespace is shared,
**every migration version must be globally unique and globally ordered across
all modules.**

## Versioning: timestamps, not incrementing integers

Filenames are `VYYYYMMDDHHMMSS__<module>_<description>.sql`, e.g.
`V20260515090300__core_tenants.sql`.

Generate the version at the moment you create the file:

```bash
date -u +V%Y%m%d%H%M%S
```

Rules:

- **Immutable once merged to `main`.** Never edit or renumber a migration that
  has been merged. Add a new timestamped file instead.
- **Never reuse a timestamp.** If `./gradlew test` fails with a Flyway
  duplicate-version error, bump your file's seconds by one and retry.
- **Cross-module dependency rule.** A migration in a non-core module that
  references a core table (FK, or a core `DOMAIN` type) must have a timestamp
  strictly **after** every core migration it depends on. In practice: ship the
  core migration in the same PR or an earlier one, and give the dependent
  migration a clearly-later time.

## One file per domain; fold ALTERs into the CREATE

Within a module, keep **one file per table or cohesive domain group**. When you
add a column or constraint to a table you own and the change has not yet
shipped, prefer a new timestamped migration with a plain `ALTER TABLE`. Periodic
consolidation — folding accumulated `ALTER`s back into the original `CREATE
TABLE` so the schema reads cleanly — is a **deliberate, gated activity**, not
something to do casually (it rewrites migration history). The last consolidation
was [#413](https://github.com/epistola-app/epistola-suite/issues/413); the
equivalence gate below is what makes it safe.

When folding an `ADD COLUMN` into a `CREATE TABLE`, place the column at the **end
of the column list** in the order the `ALTER`s originally ran — PostgreSQL
assigns columns in physical (`attnum`) order and `pg_dump` emits them that way,
so column position must be preserved for the equivalence gate to pass. Cross-table
FKs that can't be inlined (the referenced table is created in a later file) are
added with a trailing `ALTER TABLE … ADD CONSTRAINT` in the file that creates the
referenced table.

## The equivalence gate

`apps/epistola/src/test/.../migration/MigrationEquivalenceIT` builds two
PostgreSQL databases — one from the historical `V1..V30` snapshot
(`apps/epistola/src/test/resources/db/migration-legacy-v413/`), one from the
current `classpath:db/migration` — and asserts their normalised
`pg_dump --schema-only` output is **byte-identical**. It runs as a tagged
integration test (`./gradlew integrationTest` / `test`).

`apps/epistola` is the only module whose test classpath aggregates core +
feedback + loadtest migrations the way production does, so the gate also proves
cross-module version ordering.

Note the gate is **schema-only**: it cannot catch a regression in a _data_ step
(e.g. the `installation` identity seed). Data-affecting migrations must have
integration-test coverage — `InstallationServiceIT` and
`AppMetadataInstallationStoreIT` guard the `installation` seed.

> The legacy snapshot + this gate are kept through the 1.0.0 cycle plus one
> release, then removed (`TODO(#413)`).

## Adding a new migration

1. Create `<module>/src/main/resources/db/migration/<module>/` if needed.
2. Name the file `$(date -u +V%Y%m%d%H%M%S)__<module>_<description>.sql`.
3. Write the SQL. If it touches a table created by another module, ensure the
   timestamp sorts after that table's migration (see the cross-module rule).
4. Run `./gradlew :modules:<module>:test` (or `./gradlew integrationTest`). A
   Flyway duplicate-version error means you collided a timestamp — bump and
   retry.
5. If the migration changes the schema, the equivalence gate is irrelevant for
   _new_ migrations (it only pins the consolidated baseline); just ensure the
   normal integration suite passes.

## Local dev: one-time reset after a consolidation

`FlywayConfig` runs with `clean-disabled: false` in dev: if Flyway validation
fails (which it does after a history rewrite — every checksum changed), it
auto-`clean()`s and re-migrates from the new baseline. **Your local dev database
will be wiped and rebuilt on the next app start** after pulling a consolidation
change; `DemoLoader` repopulates demo data automatically. Tests use throwaway
Testcontainers databases and are unaffected. Production runs with
`clean-disabled: true` (rethrows instead) — irrelevant pre-1.0 since no
production database exists.
