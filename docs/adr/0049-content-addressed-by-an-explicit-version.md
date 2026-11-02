# ADR 0049: Content addressed by an explicit version

- **Status:** Proposed
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
resource (ADR 0036; the contract's `…/catalogs/{…}/releases/{release}/templates/{key}`). Until
`e8a7b5fbf` ("several drafts of a catalog at once", 2026-10-17) a catalog had one draft, so the version
went without saying, and every query, SQL helper, view and handler was written for "the" version of a
catalog.

That commit made the version **ambient** rather than adding it everywhere. A message that addresses one
version implements `CatalogReleaseScoped`; `SpringMediator` resolves it, and `CatalogReleaseTargeting`
stores the target twice: in a `ScopedValue` for the JVM, and in transaction-local PostgreSQL settings
for SQL (`epistola.content_version` for a draft, `epistola.release_catalog` with
`epistola.release_version` for a release). The view `catalog_current_versions` reads them, and answers
`catalogs.next_version_id` when none is set. Two functions (`catalog_version_resources`,
`catalog_version_references`) and eight views (`current_themes`, `current_templates`, …) build on it,
all in `V20261101001500__core_2_0_read_model.sql`. Tenant and catalog are bind parameters
(`versionAt(tenantParam, catalogParam)`); the version is not.

It was the right way to add drafts to code built for one: about 80 message types and 119 SQL uses of
the helpers and views kept working unchanged. It is a compatibility layer, though, and it costs:

- **A statement's rows depend on state it does not show.** The same SQL reads a different version
  depending on the transaction it runs in. `ContentVersionSqlTest` accepts a statement that joins
  `catalog_current_versions` as naming its version, and no longer scans `release_resources`, which is
  read through the views: it checks that a version is resolved, not which one.
- **The default is decided in the view, not by the caller.** "Which version does this request mean" is
  known where the request enters: a page means the default, a REST call the release in its path, a
  generation the deployed release. Today the answer is a fallback deep in SQL, so a query that means
  "every catalog's default" (search, ADR 0043) is right only as long as no caller has set a target.
- **One target per catalog per transaction.** Work that needs two versions of a catalog switches the
  ambient target back and forth: nine `targeting.within(…)` calls in five handlers
  (`ReleaseCatalogDraft`, `MergeReleaseIntoDraft`, `MergeReferenceCheck`, `CatalogDraftCommands`,
  `NextVersionChanges`).
- **Two carriers to keep in step.** A handler that opens its own transactions
  (`SelfManagedTransaction`) must call `targeting.apply(handle)` in each, or it silently reads the
  default.
- **Releases use a second pair of settings** with their own branch in the view, although a release is
  a `catalog_versions` row like a draft (ADR 0036).

None of this is visible outside the suite: no client, archive or API sees the settings. Changing it is
not tied to the 2.0 alpha, and costs the same before or after 2.0.

## Considered options

1. **Keep the ambient target and harden it.** A marker for messages that always read the default
   versions, for which the mediator clears the target, and a helper that opens a self-managed
   transaction with the target applied. Cheap, and it closes the two known holes, but the SQL stays
   implicit and new code keeps building on it. Worth doing only as a stopgap; rejected as the end state.
2. **Explicit versions everywhere, at once.** Correct, but one rewrite of about 80 message types, 119
   SQL uses and every nested dispatch, landing together. Rejected for its size and risk.
3. **Row-level security on the setting.** Makes the target enforced rather than advisory, but it is
   still ambient, and a tenant-wide read would have to switch it off. Rejected.
4. **Chosen: explicit versions, introduced beside the ambient target and moved to area by area.** New
   code is explicit from the start; existing code moves when it is touched or when an area is
   converted; the ambient target is removed when nothing reads it.

## Decision

### 1. A version is part of a content address

A message that reads or writes one version's content carries the **resolved** version, a
`catalog_versions.version_id`, typed, the way it carries its tenant key. It is a required field, so
forgetting to pass it on in a nested dispatch is a compile error, not a silent read of the default.
`CatalogReleaseRef` (`next`, a draft's id, a release number) stays the address a caller writes; it is
resolved once, where the request enters, by the `ResolveCatalogRelease` query the REST API already uses.

### 2. The caller chooses the default, by name

"The default version" is asked for explicitly: `catalogs.next_version_id`, through a `CatalogSql.kt`
helper (for example `defaultVersionAt(t, c)`, and a join for tenant-wide reads). It never reads the
settings, so a targeted transaction cannot change what it answers. It answers what
`catalog_current_versions` answers today when nothing is targeted, so moving to it changes no result.

### 3. SQL takes the version as a parameter

Statements bind `:versionId` (or join the default helper). Functions taking the version sit beside
today's: `version_resources(tenant, version_id, type)` and `version_references(…)`, with the per-type
reads as functions of the version rather than views of the ambient one. A released version is read the
same way as a draft, by its `version_id`, so the release settings go with the draft one.

### 4. Nested work passes the version on

A handler that dispatches gives the inner message the version, as it gives the tenant. A handler that
needs two versions names both; it does not switch.

### 5. The guard checks which version, not whether one

`ContentVersionSqlTest` scans `release_resources` again, and accepts a statement that binds a version
or joins the default helper. Joining `catalog_current_versions`, or reading a view built on it, is
allowed only in a list of files not yet moved, which shrinks with each step and must not grow.

### 6. The ambient target is removed last

When that list is empty, one migration replaces `catalog_current_versions` and what builds on it, and
`CatalogReleaseTargeting`, its `ScopedValue`, the three settings and `SelfManagedTransaction`'s
`apply` go.

## Consequences

- **SQL says what it reads.** A statement's rows follow from its parameters; reviewing a query no
  longer means knowing the transaction it will run in.
- **Work across versions is ordinary code.** Merging and releasing name the versions they read instead
  of switching a target, and search is correct by construction.
- **Messages that address content gain a field**, and their constructors change. Callers that dispatch
  them pass the version on.
- **Two ways coexist until the end.** The guard's list keeps the old way from spreading and shows what
  is left.
- **Each step is a forward migration or none.** New functions are added beside the old; nothing is
  rewritten in place (`v2.0.0-alpha.1` has shipped). Only the last step changes an existing view.
- **Nothing outside the suite changes**: no contract, archive or API change.

## Delivery

Each step is its own pull request, in 2.0 or later:

1. **New code is explicit.** The default-version helper in `CatalogSql.kt` (no migration), and
   `SearchTenant` (ADR 0043) reading through it as its first user. The guard's list of files not yet
   moved, with today's files in it.
2. **Functions of the version** (`version_resources`, `version_references`, the per-type reads), in one
   forward migration, beside the views.
3. **Work across versions first**: release, merge, import and next-version changes, which switch the
   target today and gain the most.
4. **Then each area**, resource type by resource type, with the REST, UI and MCP entry points resolving
   the version they pass down.
5. **Removal**: the migration and the code of §6, once the guard's list is empty.

## References

- `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/releases/CatalogReleaseScoped.kt`
  (`CatalogReleaseTargeting`)
- `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogSql.kt` (`versionAt`,
  `inCurrentVersion`)
- `modules/epistola-core/src/main/resources/db/migration/core/V20261101001500__core_2_0_read_model.sql`
  (`catalog_current_versions` and what builds on it)
- `modules/guards/src/test/kotlin/app/epistola/suite/guards/ContentVersionSqlTest.kt`
- [`docs/catalog-versioning.md`](../catalog-versioning.md), "Which version a statement means"
