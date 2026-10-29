# Catalog and Exchange

Catalog exchange lives here, in `app.epistola.suite.catalog`: import/export, remote catalog clients,
the bundled `system` catalog (the demo one ships in `apps/epistola-demo`), the tenant snapshot
primitives in `snapshot/`, and reference discovery in `graph/`. There is no separate catalog module.

## The dependency rules

`catalog` must **not** reference `exchange`. `CatalogExchangeIndependenceTest` checks this in
bytecode, so a fully qualified reference fails too — there is no import line to grep for.

The domains depend on `catalog`, never the reverse (ADR 0039; `CatalogBoundaryTest` allows none): a
type's particulars are its domain's `ResourceType` and `ResourceRelocator`, anything else an SPI
catalog owns (`CatalogDraftLifecycle`, `ReleaseCheck`, `CatalogAttributeValues`).

Every SQL statement reading or writing catalog content names the catalog version it means, since a
catalog may hold several drafts; `ContentVersionSqlTest` finds those that do not.

A released version never changes (ADR 0036, the freeze): every writer of a version's rows refuses a
released one first (`requireWritableVersion`, `Handle.writable` or `inDraft`), or belongs to the
release path; `FrozenVersionWriteGuardTest` holds that.

A release is a release however it arrived (ADR 0036): a catalog's version is its highest release.
How a release came here (`released_via`, `released_from`, `ReleaseChannel`) is recorded where it
arrives and shown where it is described, never branched on; `ReleaseProvenanceUsageTest` lists the
files that may name it.

- **Outbound**: `ReleaseCatalogDraft` records publication intent through
  `CatalogReleasePublicationPort` (owned by `catalog`), handed the open release transaction so the
  outbox write stays atomic without inverting the dependency.
- **Inbound needs no port**: installing calls _into_ `catalog` (`ImportCatalogZip`), and Exchange
  contributes a `CatalogUpstreamProbe`, an interface `catalog` owns. So `exchange → catalog` is the
  only direction.
- **Release registries**: the Exchange is one kind of release registry (contract ADR 0008) beside
  the container registries; `exchange` contributes it as a `ReleaseRegistryProvider`, an interface
  `catalog/registry` owns.

`ExchangeAvailability` is the single answer to "may this tenant publish?" and "may this tenant
install?" — deployment gate plus the `catalog-publishing` / `catalog-installing` features.
`ExchangeNamespaceBinder` owns the immutable namespace rule, `CatalogPublicationStore` the outbox
SQL, `CatalogUpstreamCheckStore` (in `catalog/`) the upstream-check SQL. UI reads
`GetCatalogPublicationState`, `GetExchangeSettings` and `GetCatalogUpstreamStates` rather than
recomposing any of it. An installed catalog records its origin as a URI in `catalogs.source_url`
(`exchange:namespace/key`), so a new source kind is a new scheme, not a migration.

Background: [`docs/catalog-exchange-publication.md`](../../../../../../../../../docs/catalog-exchange-publication.md),
[`docs/catalog-exchange-installation.md`](../../../../../../../../../docs/catalog-exchange-installation.md),
ADR 0018 and ADR 0021.

## Threading a per-resource field

Export reads the stored wire form as it is (ADR 0039), so a member round-trips when it is in the
`epistola-contract` protocol model, its type's `receive` passes it on (for templates `ImportTemplates`,
the insert **and both** update paths), and the domain's store writes it into the content. Missing from
any one of those, it is silently dropped on re-import. Add a round-trip test rather than trusting a
read of the code.

## Bundled catalogs

Touching `catalogs/system/` here, or `catalogs/demo/` in `apps/epistola-demo`, means bumping that
catalog's `release.version` (SemVer, strictly increasing) **and** regenerating `release.fingerprint`
in `catalog.json`. Loaders detect change by fingerprint, not by the version string, so a stale
fingerprint ships unchanged content silently. See
[`.agents/rules/bundled-catalogs.md`](../../../../../../../../../.agents/rules/bundled-catalogs.md).

## Resource references

Tenant-wide reference discovery and traversal live in `graph/`
([`docs/resource-reference-graph.md`](../../../../../../../../../docs/resource-reference-graph.md)).
A new catalog-resource reference shape must be added to that authority with resolution and lifecycle
evidence. The existing deletion and upgrade scanners stay independent until deliberately migrated
and parity-tested.

## Verify

```bash
./gradlew :modules:epistola-core:integrationTest --tests "*Catalog*"
./gradlew :modules:epistola-core:unitTest --tests "*BundledCatalogFingerprintTest"
./gradlew :apps:epistola-demo:unitTest --tests "*DemoCatalogFingerprintTest"
```
