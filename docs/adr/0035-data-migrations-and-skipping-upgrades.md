# ADR 0035: Data migrations, and upgrading across any number of versions

- **Status:** Accepted (2026-10-23; first applied by ADR 0033's storage, in 2.0)
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
isolated context without the domain); `CatalogUpgradeRunner`, a cluster task that cut each 1.x
catalog's first 2.0 release with the suite's code after startup (since removed, below); the upgrade preflight that refuses an upgrade that
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

- **Self-contained**: it uses JDBC, a JSON tree (Jackson `ObjectNode`) and frozen code only (below).
  No domain classes, no mediator, no unversioned protocol model. What it knows (how the columns of
  that time map to the wire form of that time) is fixed and is written into it. A guard test refuses
  an import in the migration package of anything but frozen code.
- **Frozen**: never changed once merged, as an SQL migration. Flyway gives a code migration no
  checksum of its own, so it declares one (`getChecksum`), pinned to its source, and
  `checkMigrationVersions` treats it as it treats SQL.
- **Ordered before the application**: the migration step runs before any node serves, so whatever
  version an installation comes from, the conversion has run before the code that expects its result.
- **Tested from every major**: `DataPreservationMigrationIT` upgrades a 1.x database (and one of each
  later major, as they ship) to the current version on every build, through every migration.

### Frozen code

A conversion often needs more than a column mapping: a content hash, the references a document holds.
A code migration may call such logic where it is **frozen**: a versioned algorithm whose version,
once released, never changes meaning, such as the contract's fingerprints (V5, V6). It is frozen by
**golden vectors**, not by its source: a fixed set of inputs with their expected outputs per version,
pinned where the algorithm lives (the contract's `fixtures/v1/fingerprints.json`, checked by `CatalogCanonicalizerTest`) and tested again in the suite against the contract version it uses (a copy of the golden archive and its expected values, as test data), so the library may be
refactored freely as long as each version computes what it always did. A hash of the source is not
used: it fails on every harmless change and says nothing about the result.

Logic of the suite that is not frozen this way (domain code, the release assembler) is not called:
the migration reads what it needs from the tables itself, or that piece is first made a versioned
algorithm with its own vectors. Schema validation is not needed: content that does not validate is
converted as it is, never refused, and the suite reports it (a quality finding) once it runs.

What cannot be done that way is not done after startup instead: it is a deliberate stop (below).

The reference walker (what content refers to, the `resource_references` index) is frozen the same
way, by `fixtures/references/v1.json` in the suite. A change to what it finds in content that already
exists fails those vectors and comes with a code migration that walks the existing content again; a
new kind of reference only new content can contain adds a vector and needs no migration.

### Runtime backfills become migrations

A backfill that 1.x ran after startup, and an installation may skip past, moves into the migration
step when it can be self-contained. The content backfill (#738, `ContentBackfillRunner`) did:
`V20261101000500` moves the blobs still in the 1.x `content_store` with JDBC and SHA-256, before the
fonts are converted, so a face whose bytes were still there joins its font, and the job, its fallback
reads and the table are gone. It runs outside Flyway's transaction, a batch per transaction, and is
idempotent, so what committed before a failure is kept. Flyway records no history row for a
non-transactional migration that fails, so the next run of the migration step simply runs it again and
it does the rest, with no `flyway repair` (`FailedMigrationRerunIT` holds that for the Flyway in use).

The release content-hash backfill (`ReleaseContentHashBackfill`) was dropped instead: it had nothing
to hash. 1.x kept no release content, the 2.0 upgrade does not carry the 1.x releases over (`V20261101001400`)
and the upgrade cuts no release, and cutting or receiving a release records its content
hash, so a released version always has its content hash (`chk_catalog_versions_released`) and nothing computes one
later.

**The 2.0 upgrade cuts no release, so nothing runs after startup.** `CatalogUpgradeRunner` cut each
1.x catalog's first release with the suite's release code after startup; it is gone. Cutting a
release needs a great deal of live code (building the released form, checks, storage, pins), which no
migration may call, and a frozen copy of it was rejected below. So the migration step only drafts:
`V20261101000900` keeps each authored 1.x catalog's next draft and makes a named draft "Published in
1.x" of what it last published (JDBC, a JSON tree and the frozen toolkit, `db/migration/core/frozen/`), declares by name what each uses, and lists the 1.x catalogs for the tenant home. Their
authors release those drafts through the normal flow, which pins a dependency declared by name to its
catalog's highest release. A catalog 1.x subscribed to from a URL receives its source's release at
boot, as any install does. After an upgrade environments start empty anyway, so a release cut by hand
costs one step that deploying needed already.

### Stops, when they cannot be avoided

When a conversion cannot be self-contained, or a later change cannot keep an old migration runnable,
the release says so: the upgrade
preflight (#1115) refuses an upgrade from before the stop with what to upgrade to first, and #1123
refuses to start on a schema it cannot run against. A stop is recorded in the CHANGELOG and the
upgrade notes of that release.

### Rolling upgrades

A migration that moves tables cannot run beside nodes of the previous version, which still read them.
Such a release is a non-rolling upgrade (stop, migrate, start), said so in its upgrade notes, and kept
rare: ADR 0033's storage ships all its resource types in one such release, 2.0, which converts straight
from the 1.x content tables during the 1.x → 2.0 upgrade.

## Consequences

- 1.x to any later version keeps working, which the upgrade test proves on every build.
- A code migration calls only frozen code; what a conversion needs of the suite's logic is either made
  a versioned algorithm with golden vectors, or read from the tables by the migration itself.
- No data migration runs after startup, so an installation never serves half-migrated data.
- The migration rules card (`.agents/rules/migrations.md`) and `docs/migrations.md` gain code
  migrations when the first of them is built, with the guard and the checksum.

## Alternatives considered

- **Required stops at every major.** Simpler for us, and the usual choice, but every customer more than
  one major behind steps through each one, with a backup and a check each time. Kept as the exception.
- **Migrations that run with the application's code after startup** (as the content backfill and
  `CatalogUpgradeRunner` did). They need no freezing, but they run with the code of the version being installed: a 2.x
  conversion run by 3.x code breaks the first time the classes it calls change, and the application
  serves while they run. Rejected: what they need is fixed knowledge, and belongs in the migration.
- **Keeping replaced tables in their own schema** until the versions they came from reach end of life,
  to convert again after a bug. Not now: a conversion is proven by the upgrade test before it ships,
  and the tables go once converted. Can be added for a conversion that warrants it.
- **A frozen copy of the logic inside each migration.** Safe, but more than a thousand duplicated
  lines for the content hash alone, again for every migration that needs it; golden vectors freeze
  the library in place instead.

## Related

- [ADR 0033](0033-reading-any-release.md): the storage that first needs this.
- [`.agents/rules/migrations.md`](../../.agents/rules/migrations.md), [`docs/migrations.md`](../migrations.md).
