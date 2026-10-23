# ADR 0035: Data migrations, and upgrading across any number of versions

- **Status:** Accepted (2026-10-23; first applied by ADR 0033's storage, after 2.0)
- **Date:** 2026-10-23
- **Deciders:** Epistola team
- **Tags:** migrations, upgrades, database, stability

## Context

The stability contract says data is never broken: every schema change is a forward, data-preserving
Flyway migration (`.agents/rules/migrations.md`). That covers SQL. It does not say how data is
converted when SQL is not enough, and it does not say from which versions an installation may
upgrade.

ADR 0033 moves every resource from relational content tables to JSON. Converting the rows is more
than SQL can say comfortably, and parts of it (the content hash, schema validation, the reference
index) need the suite's own code. Installations, many of them municipalities, often lag several
versions behind, so an upgrade from 1.x straight to 3.x is a real case.

Other applications take one of two approaches:

- **Required upgrade stops** (GitLab, Nextcloud, Mattermost, Atlassian): an installation may only
  upgrade from certain versions, because data migrations run in the background or use application
  code that is later removed. Simple to build, and it is to upgrades what a deprecation path is to an
  API. Customers must step through each stop.
- **Cumulative migrations** (Rails and Django applications, Keycloak, Discourse): every migration stays
  in every release and they run in order from any old version. It works as long as a data migration
  never depends on current application code: Django's migrations use historical models, frozen at
  the time they were written, not the current ones.

What the suite has today: Flyway SQL migrations in the migration step (`MigrationLauncher`, an
isolated context without the domain); `ReleaseContentHashBackfill`, a cluster task that needs the
suite's code and runs after startup, unversioned; the upgrade preflight that refuses an upgrade that
could not finish (#1115); a refusal to start on a schema the build cannot run against (#1123); and
`DataPreservationMigrationIT`, which upgrades a 1.x database to the current version on every build.

## Decision

**An installation can upgrade from any earlier version to any later one**, by cumulative migrations.
A required stop is a deliberate exception, not a way of working. **Every data migration is a Flyway
migration**, run in the migration step before any node serves, versioned and run once, in order; none
is a job that runs after startup. They come in two kinds.

### 1. SQL migrations

As today: `VYYYYMMDDHHMMSS__<module>_<desc>.sql`, in the migration step, never edited once merged.

### 2. Self-contained code migrations

For a conversion SQL cannot say well, a Kotlin migration implementing Flyway's `JavaMigration`,
versioned like an SQL one (`VYYYYMMDDHHMMSS__<module>_<desc>`) and ordered among them, run in the
migration step and recorded in `flyway_schema_history`.

- **Self-contained**: it uses JDBC and a JSON tree (Jackson `ObjectNode`) only. No domain classes, no
  mediator, and no protocol classes from the contract, which change with every contract version. What
  it knows (how the columns of that time map to the wire form of that time) is fixed and is written
  into it. A guard test refuses an import of the suite's domain or of the contract in the migration
  package.
- **Frozen**: never changed once merged, as an SQL migration. Flyway gives a code migration no
  checksum of its own, so it declares one (`getChecksum`), pinned to its source, and
  `checkMigrationVersions` treats it as it treats SQL.
- **Ordered before the application**: the migration step runs before any node serves, so whatever
  version an installation comes from, the conversion has run before the code that expects its result.
- **Tested from every major**: `DataPreservationMigrationIT` upgrades a 1.x database (and one of each
  later major, as they ship) to the current version on every build, through every migration.

### What a code migration needs of the suite's logic

A conversion often needs more than a column mapping: a content hash, the references a document holds,
a default the domain fills in. Each of those is a pure function over knowledge fixed at the time of
writing (fingerprint V6 is canonical JSON and SHA-256, and the contract never changes it under that
name; the references of a wire version are where that version puts them), so the migration carries
its own frozen copy, with a test that it agrees with the suite's code at the time it is written.
Schema validation is not one of them: content that does not validate is converted as it is, never
refused, and the suite reports it (a quality finding) once it runs.

What cannot be done that way is not done after startup instead: it is a deliberate stop (below).
`ReleaseContentHashBackfill`, a cluster task from before this decision, stays as it is until it can
be retired; no new data migration takes its form.

### Replaced data is kept until a declared end of life

A migration that replaces tables (ADR 0033's content tables) moves them into their own schema
(`ALTER TABLE … SET SCHEMA legacy_v1`: no copy, no extra space) instead of dropping them, so the
original data stays for converting again after a bug, and for support. They are removed by one
deliberate migration once the versions they came from are declared end of life. That removal drops
data that exists in its new form, which is the only exception to "no destructive migrations", and it
is named as such in its migration.

### Stops, when they cannot be avoided

When a conversion cannot be self-contained, or a later change cannot keep an old migration runnable,
the release says so: the upgrade
preflight (#1115) refuses an upgrade from before the stop with what to upgrade to first, and #1123
refuses to start on a schema it cannot run against. A stop is recorded in the CHANGELOG and the
upgrade notes of that release.

### Rolling upgrades

A migration that moves tables cannot run beside nodes of the previous version, which still read them.
Such a release is a non-rolling upgrade (stop, migrate, start), said so in its upgrade notes, and kept
rare: ADR 0033's storage ships all its resource types in one such release.

## Consequences

- 1.x to any later version keeps working, which the upgrade test proves on every build.
- A code migration copies the knowledge it needs instead of calling the domain; that is duplicated
  code, deliberately, since it must not change when the domain does.
- No data migration runs after startup, so an installation never serves half-migrated data.
- Replaced tables cost their space until the end of life of the versions they came from.
- The migration rules card (`.agents/rules/migrations.md`) and `docs/migrations.md` gain code
  migrations when the first of them is built, with the guard and the checksum.

## Alternatives considered

- **Required stops at every major.** Simpler for us, and the usual choice, but every customer more than
  one major behind steps through each one, with a backup and a check each time. Kept as the exception.
- **Migrations that run with the application's code after startup** (as `ReleaseContentHashBackfill`
  does). They need no freezing, but they run with the code of the version being installed: a 2.x
  conversion run by 3.x code breaks the first time the classes it calls change, and the application
  serves while they run. Rejected: what they need is fixed knowledge, and belongs in the migration.
- **Copying the replaced tables into a backup before dropping them.** Moving them to their own schema
  keeps the same data at no cost.

## Related

- [ADR 0033](0033-reading-any-release.md): the storage that first needs this.
- [`.agents/rules/migrations.md`](../../.agents/rules/migrations.md), [`docs/migrations.md`](../migrations.md).
