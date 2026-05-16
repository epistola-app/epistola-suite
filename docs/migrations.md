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
  has been merged. Add a new timestamped file instead. The **only** exception is
  a deliberate, gated consolidation/rewrite while the project is pre-1.0 with no
  production deployments (see below) — editing baseline files in place is then
  acceptable because every database is rebuilt from scratch.
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
something to do casually (it rewrites migration history). Recent consolidations:
[#413](https://github.com/epistola-app/epistola-suite/issues/413) (per-module
restructure) and the schema-standardization rewrite that established the current
conventions — canonical `created_at`/`updated_at` and `created_by`/`updated_by`
audit columns (audit FKs to `users(id)` are `ON DELETE SET NULL`, the sole
exception being `feedback.created_by`, which is mandatory `NOT NULL`),
`correlation_id` (not `correlation_key`), `TIMESTAMPTZ`, lowercase boolean
literals, and the `TENANT_KEY` domain for every tenant slug column. Because the
audit FKs are real, every principal that writes must be a `users` row. The
single canonical **`SystemUser`** (all-zeros UUID) is seeded by the `core_users`
baseline migration — a database invariant, the same approach as the
`installation` row — and owns every system-initiated write (background
generation, demo bootstrap, system-catalog install/upgrade). Real users come
from the auth paths (OAuth2 / config-driven local provisioning); integration
tests materialise a row for whatever principal the harness binds via
`TestPrincipalUsers` (no fixed seed list) — see
[`docs/testing.md`](testing.md). When seeding system/identity rows in a
baseline migration, mirror the constant in code (e.g.
`app.epistola.suite.security.SystemUser`) and cross-reference both ways.

When folding an `ADD COLUMN` into a `CREATE TABLE`, place the column at the **end
of the column list** in the order the `ALTER`s originally ran — PostgreSQL
assigns columns in physical (`attnum`) order and `pg_dump` emits them that way,
so column position must be preserved for the consolidated schema to come out
identical to the history it replaces. Cross-table FKs that can't be inlined (the
referenced table is created in a later file) are added with a trailing
`ALTER TABLE … ADD CONSTRAINT` in the file that creates the referenced table.

## How the #413 consolidation was verified

The consolidation was proven correct **before merge** with a one-shot
equivalence check: two PostgreSQL databases were built — one from the historical
`V1..V30` migrations, one from the new per-module baseline — and their
normalised `pg_dump --schema-only` output was asserted **byte-identical**
(`apps/epistola` is the only module whose classpath aggregates core + feedback +
loadtest the way production does, so this also exercised cross-module version
ordering). That harness and the historical snapshot were removed once verified;
they would only ever go from "pass" to a false failure the moment a new
migration legitimately diverged from the frozen history.

A schema-only check cannot catch a regression in a _data_ step (e.g. the
`installation` identity seed). Data-affecting migrations must therefore have
integration-test coverage — `InstallationServiceIT` and
`AppMetadataInstallationStoreIT` guard the `installation` seed.

## Adding a new migration

1. Create `<module>/src/main/resources/db/migration/<module>/` if needed.
2. Name the file `$(date -u +V%Y%m%d%H%M%S)__<module>_<description>.sql`.
3. Write the SQL. If it touches a table created by another module, ensure the
   timestamp sorts after that table's migration (see the cross-module rule).
4. Run `./gradlew :modules:<module>:test` (or `./gradlew integrationTest`). A
   Flyway duplicate-version error means you collided a timestamp — bump and
   retry.
5. Ensure the normal integration suite passes — a new migration is just appended
   to the history; there is no special gate to satisfy.

## Local dev: one-time reset after a consolidation

`FlywayConfig` runs with `clean-disabled: false` in dev: if Flyway validation
fails (which it does after a history rewrite — every checksum changed), it
auto-`clean()`s and re-migrates from the new baseline. **Your local dev database
will be wiped and rebuilt on the next app start** after pulling a consolidation
change; `DemoLoader` repopulates demo data automatically. Tests use throwaway
Testcontainers databases and are unaffected. Production runs with
`clean-disabled: true` (rethrows instead) — irrelevant pre-1.0 since no
production database exists.
