# ADR 0049: Content addressed by an explicit version

- **Status:** Accepted (2026-11-02; revised 2026-11-02: at once, before 2.0 GA, rather than area by area)
- **Date:** 2026-11-02
- **Deciders:** Epistola team
- **Tags:** catalog, versions, database, mediator, sql
- **Related:** [ADR 0033](0033-reading-any-release.md) (reading any release),
  [ADR 0036](0036-one-table-for-every-catalog-version.md) (one table for every catalog version, §7),
  [ADR 0038](0038-catalogs-in-2-0.md) (catalogs in 2.0, reads by role through views),
  [ADR 0043](0043-tenant-search.md) (tenant search), contract ADR 0002 (several drafts per catalog),
  [`docs/catalog-versioning.md`](../catalog-versioning.md), "Which version a statement means"

## Context

Content belongs to a catalog version: tenant, catalog, version, type and key together address a
resource (ADR 0036; the contract's `…/catalogs/{…}/releases/{release}/templates/{key}`). Every version
of a catalog lives in the same tables, so a statement on content has to say which version's rows it
means. Until `e8a7b5fbf` ("several drafts of a catalog at once", 2026-10-17) a catalog had one draft,
so the version went without saying, and every query, SQL helper, view and handler was written for
"the" version of a catalog.

That commit made the version **ambient** rather than adding it everywhere. A message that addresses one
version implements `CatalogReleaseScoped`; `SpringMediator` resolves it, and `CatalogReleaseTargeting`
stores the target twice: in a `ScopedValue` for the JVM, and in transaction-local PostgreSQL settings
for SQL (`epistola.content_version` for a draft, `epistola.release_catalog` with
`epistola.release_version` for a release). The view `catalog_current_versions` reads them, and answers
`catalogs.next_version_id` when none is set. Two functions (`catalog_version_resources`,
`catalog_version_references`) and eight views (`current_themes`, `current_templates`, …) build on it,
in `V20261101001500__core_2_0_read_model.sql`, and the quality module's two (`V20261101002000`) on
those.

It was the quickest way to add drafts to code built for one, and it costs:

- **A statement's rows depend on state it does not show.** The same SQL reads a different version
  depending on the transaction it runs in.
- **The default is decided in the view, not by the caller.** A query that means "every catalog's
  default" (search, ADR 0043) is right only as long as no caller has set a target.
- **One target per catalog per transaction.** Work that needs two versions switches the target back
  and forth (`targeting.within`, `VersionReads.at`).
- **Two carriers to keep in step.** A handler that opens its own transactions must reapply the target,
  or it silently reads the default.
- **Releases have their own branch in SQL**, matched by catalog and number, although a release is a
  `catalog_versions` row like a draft (ADR 0036).
- **It is in the migrations.** After 2.0.0 is GA a migration is never edited again, so a read model
  whose meaning depends on session state would stay in the schema for good.

## Considered options

1. **Keep the ambient target and harden it.** Rejected: the SQL stays implicit.
2. **Keep the schema's notion of "the version a caller means", as functions of arguments.** The views
   become functions of the target, and Kotlin binds it to every statement. Rejected (it was #1265):
   it cleans the session state out of the migrations but freezes the old view's choice, release
   branch included, in them, and the target stays ambient in Kotlin.
3. **Explicit versions area by area, behind a guard, after GA.** The original decision of this ADR.
   Rejected once it was clear the schema must be settled before GA: the end state is the same, and
   doing it in steps keeps two ways of reading content side by side for months.
4. **Chosen: explicit versions everywhere, at once, before 2.0 GA**, as if there had never been an
   ambient target.

## Decision

### 1. The schema knows versions, not "the version a caller means"

The read model is a set of views of **every** version's content, each row with its `version_id`
(`version_themes`, `version_code_lists`, `version_attributes`, `version_fonts`, `version_images`,
`version_stencils`, `version_templates`, `version_template_variants` and `version_references`, beside
`release_wire_resources` for the plain rows; the quality module's `quality_version_variants` and
`quality_version_checks`). A view only turns stored content into columns. It never chooses a version
and reads nothing from the session, so it means the same in every transaction. A statement filters it
by the version it means: `WHERE t.version_id = :v`.

`catalog_current_versions`, `catalog_version_resources`, `catalog_version_references` and the
`current_*` views are gone. This is done in `V20261101001500` itself, which `v2.0.0-alpha.1` shipped;
the 2.0.0-alpha.1 repair script puts the new views in place of alpha.1's. `VersionTargetNotInSessionTest`
keeps the settings out of every migration and statement.

### 2. A message carries the version it addresses, as it carries its tenant

A message that reads or writes one catalog's content says which version: `catalogVersion`, a
`CatalogReleaseRef` (the catalog, and `next`, a draft's id or a release number), the address a caller
writes. Its handler resolves it through `ContentVersions`, the one place an address becomes a
`version_id` and where "no such draft" and "no such release" are raised, and binds the result.

It carries the address rather than a resolved id for the reason it carries a tenant key rather than a
tenant row: callers write addresses, and resolving them, with the errors that go with it, belongs in
one place rather than at every REST, UI and MCP entry point.

### 3. The caller chooses the default, by name

"The default version" is `catalogs.next_version_id`, asked for explicitly: a message whose
`catalogVersion` is null means its catalog's next version, and a tenant-wide read joins each catalog's
next version (`inNextVersion`). Nothing a caller set elsewhere changes what it answers.

### 4. Nested work passes the version on

A handler that dispatches gives the inner message the version, as it gives the tenant. A handler that
needs two versions names both; it does not switch. Nothing is inherited: a message dispatched without
a version reads the default, whatever its caller read.

### 5. A read of another catalog names its version too

Where content of one catalog is read for another's (a template's theme, a stencil, a code list from a
dependency), the reader names the version it reads: the one the dependency declaration pins or names,
as the theme and stencil resolvers already do, or that catalog's next version. The conversion keeps
each such read's result; it only makes the choice visible.

### 6. Writes name their version too

A writer resolves its version like a reader and refuses a released one
(`CatalogReleaseIsReleasedException`, 409: open the next draft) before it writes: the freeze of ADR
0036, on the version it was given rather than the one a transaction targets.

## Consequences

- **SQL says what it reads.** A statement's rows follow from its parameters; reviewing a query no
  longer means knowing the transaction it will run in.
- **Work across versions is ordinary code.** Merging and releasing name the versions they read instead
  of switching a target, and search is correct by construction.
- **`CatalogReleaseTargeting`'s target, `VersionReads` and the three settings are gone**, and with them
  `targeting.apply` in self-managed transactions.
- **The schema is settled before GA.** `V20261101001500` is rewritten once: databases alpha.1 migrated
  run the repair script (`docs/upgrades.md`, "From 2.0.0-alpha.1"), and a later version refuses to
  start on one that has not, changing nothing.
- **Nothing outside the suite changes**: no contract, archive or API change.

## References

- `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/releases/ContentVersions.kt`
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogSql.kt`
- `modules/epistola-core/src/main/resources/db/migration/core/V20261101001500__core_2_0_read_model.sql`
- `modules/guards/src/test/kotlin/app/epistola/suite/guards/ContentVersionSqlTest.kt`,
  `VersionTargetNotInSessionTest.kt`
- [`docs/catalog-versioning.md`](../catalog-versioning.md), "Which version a statement means"
