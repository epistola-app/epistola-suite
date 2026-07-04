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

| Module                              | Subfolder                | Owns                                                                  |
| ----------------------------------- | ------------------------ | --------------------------------------------------------------------- |
| `modules/epistola-core`             | `db/migration/core/`     | Everything except the tables below (tenants, templates, documents, …) |
| `modules/epistola-audit`            | `db/migration/audit/`    | `audit_log`                                                           |
| `modules/epistola-support-feedback` | `db/migration/feedback/` | `feedback`, `feedback_comments`, `feedback_assets`                    |
| `modules/epistola-support-backups`  | `db/migration/backups/`  | `tenant_backups`                                                      |
| `modules/loadtest`                  | `db/migration/loadtest/` | `load_test_runs`                                                      |

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
TABLE` so the schema reads cleanly — rewrites migration history and is therefore
**no longer permitted**: as of **1.0.0-RC1** the database is stable and is never
reset, so a history rewrite would discard existing data. Past consolidations (all
pre-RC1, when the database could still be wiped):
[#413](https://github.com/epistola-app/epistola-suite/issues/413) (per-module
restructure), the schema-standardization rewrite that established the current
conventions, and the **1.0.0-RC1** pass (the last such clear) — folding
the post-baseline `ALTER`/rename/drop patches back into their `CREATE`s, collapsing
the cluster scheduled-task migrations, and pruning redundant indexes — canonical
`created_at`/`updated_at` and `created_by`/`updated_by`
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
6. **If the new table is tenant-scoped** (has a `tenant_key` column, or is keyed
   by the tenant itself), classify it in your module's `TenantBackupTableContributor`
   bean — `includedTables()` to back it up or `excludedTables()` to exclude it.
   `TenantTableTopologyDriftIntegrationTest` (and `TenantBackupClassificationAppTest`
   for feature modules) fails the build until you do. See [`tenant-backup.md`](tenant-backup.md).

## Running migrations: embedded vs separated

A single knob — `epistola.migration.mode` (env `EPISTOLA_MIGRATION_MODE`),
branched in `FlywayConfig`'s `FlywayMigrationStrategy` (Spring Boot's only
run-instead-of-`migrate()` seam) — has three unambiguous values:

- **`embedded` (default — local/dev).** The app runs `flyway.migrate()` at boot,
  exactly as before. Idempotent: a no-op when the schema is at head.
- **`migrate` (the dedicated migration step).** `main()` detects this env value
  before Spring starts and hands off to `MigrationLauncher` (isolated context,
  migrate, exit). Reserved for the migration step and never an `application.yaml`
  default, so `embedded` can never accidentally trigger the launcher.
- **`validate` (separated app pods — set by the `prod` profile and the Helm
  chart in `job`/`initContainer` modes).** The app **never migrates or cleans**.
  It validates the schema and fails fast (non-zero exit) if the database is
  behind, so pods refuse to serve traffic until the separate migration step has
  run.

The separate migration step is the **same image** launched with
`EPISTOLA_MIGRATION_MODE=migrate` (or `--migrate`). `main()` detects this before
Spring starts and hands off to `MigrationLauncher`, which boots an isolated context
importing only the datasource + Flyway auto-config + `FlywayConfig` (no web
server, schedulers, demo loader or catalog bootstrap — nothing to gate), runs
`flyway.migrate()`, and exits 0 on success / 1 on failure. The same
`application.yaml` loads, so `spring.flyway.locations` and the
`SPRING_DATASOURCE_*` config are identical to the embedded path — no Flyway-config
duplication, no migrate/runtime drift. The migration step always forces
`clean-disabled: true` (a failed migration must surface as a non-zero exit, never
a destructive reset). The isolated runner (`MigrationLauncher.migrationApplication()`,
shared by production and the migration tests) deliberately excludes Spring Boot's
`LoggingApplicationListener` so it never initialises or cleans the JVM-global
Logback context — it logs at Logback's default formatting and never disturbs the
app's shared logging/turbo-filter state.

In `validate` mode the app pods never migrate; a behind schema throws
`SchemaBehindException` (a Spring Boot `ExitCodeGenerator`) so the process exits
non-zero through Spring Boot's own failure handling — `main()` is a bare
`runApplication`, no `try/catch`/`exitProcess`.

Kubernetes wiring is the Helm `migration.mode` value (`job` default /
`initContainer` / `embedded`) — see [`deployment.md`](deployment.md).

## Local dev: one-time reset after a consolidation

This applies only with `epistola.migration.mode=embedded` (the local/dev
default) **and** the `local` profile active. `application-local.yaml` sets
`clean-disabled: false`; if Flyway validation fails (which it does after a
history rewrite — every checksum changed), `FlywayConfig` auto-`clean()`s and
re-migrates from the new baseline. **Your local dev database will be wiped and
rebuilt on the next app start** after pulling a consolidation change;
`DemoLoader` repopulates demo data automatically. Tests use throwaway
Testcontainers databases and are unaffected. This auto-clean is a **local-dev
convenience only**; in normal operation it no longer triggers, since
history-rewriting consolidations are no longer permitted (see above).

### Reset is local-only, by construction

The destructive `flyway.clean()` is gated in depth so a real database can never
be reset:

1. **Default-deny config.** The base `application.yaml` sets
   `clean-disabled: true`. Only `application-local.yaml` overrides it to `false`.
2. **Hard code guard, two conditions.** `FlywayConfig.resolveCleanDisabled` only
   permits clean when **both** the `local` profile is active **and** the
   datasource host is loopback (`127.0.0.0/8`, `localhost`, or `::1`) — otherwise
   it force-disables clean, overriding the property even if
   `spring.flyway.clean-disabled=false` is passed via env/args (it logs a warning
   and keeps clean disabled). The loopback check specifically defends against the
   `local` profile being (accidentally) enabled in production: a real database is
   never on `127.x.x.x`, so clean stays disabled regardless. Host detection
   fails closed — an unparseable or multi-host (HA) URL is treated as non-local.

Production additionally runs the separated migration step (`clean-disabled: true`,
rethrows on validation failure) with app pods in `validate` mode — several
independent reasons a real database is never cleaned, upholding the 1.0.0-RC1
guarantee that data persists across versions.
