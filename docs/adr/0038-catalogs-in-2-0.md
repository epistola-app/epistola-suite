# ADR 0038: Catalogs in 2.0 — versions, releases, identity, storage

- **Status:** Accepted — implemented (2.0)
- **Date:** 2026-10-27
- **Deciders:** Epistola team
- **Tags:** catalog, releases, drafts, storage, upgrades
- **Supersedes:** [ADR 0026](0026-revisions-releases-and-the-working-copy.md),
  [ADR 0033](0033-reading-any-release.md), [ADR 0036](0036-one-table-for-every-catalog-version.md);
  in part [ADR 0032](0032-catalog-sources-releases-and-merging.md) (§9, the table fold) and
  [ADR 0034](0034-content-addressed-releases-and-named-pointers.md) (the suite's storage and the
  release-hash backfill)
- **Related:** [ADR 0035](0035-data-migrations-and-skipping-upgrades.md) (how the data got here),
  [ADR 0037](0037-resource-identity-and-relocation.md) (identity and relocation); contract
  ADR 0002 (drafts and released versions), 0003 (catalog identity and distribution), 0006 (stencil
  instances by reference), 0008 (release registries)

## Context

2.0 replaced per-template versions with releases of a whole catalog (ADR 0032, contract ADR 0002),
and moved every resource out of its own tables into one JSON storage. The decisions were taken in
steps — 0026, 0033, 0034, 0036 and many PRs — each correct when written and partly overtaken by the
next. This record is the single description of the end state: what a catalog version is, where its
resources live, how releasing, receiving and upgrading work. The earlier ADRs stay as history.

## Decision

### 1. One row per catalog version

- `catalog_versions` holds every version of every catalog, draft or released, keyed by `version_id`
  from creation to deletion. `state` is `draft` or `released`; `origin` is `authored` (cut here) or
  `installed` (received from elsewhere: upload, OCI registry, Exchange, a URL source).
- A version has one public id, `version_key` (the contract's `CatalogVersionId`), from the draft on;
  a release also has its number (`version`), unique per catalog, and its `fingerprint`,
  `content_hash`, `notes`, `manifest_snapshot`, `released_at`, `released_by`,
  `rendering_defaults_version` and frozen settings (default theme, presets). A version's base is
  `base_version_id`, with `base_version`/`base_fingerprint` kept for a base that is not installed
  here.
- A catalog has several drafts; the one it is working towards is `catalogs.next_version_id`, held to
  a draft of that catalog by a deferred check.
- **Releasing freezes the draft's row**: it becomes the release, keeping its id; the next draft is a
  new row that copies its content (`copy_catalog_version_content`). A released row never changes;
  every writer refuses one, and a save through a released id is answered `catalog-version-released`
  (409) with the next draft to open (`FrozenVersionWriteGuardTest`).
- What names a version names the row: deployments (`environment_catalog_deployments.target_version_id`),
  dependency declarations (`catalog_version_dependencies`), merges, publications, ready marks,
  refreshes. A deployed draft and a declaration on another catalog's draft **follow their row into
  the release**; serving or depending on the next draft is an explicit new deployment or declaration
  (contract ADR 0002/0003).
- Deleting a release removes its row; it is refused while another release pins it or an
  environment deploys it. A release a draft was started from may be deleted.
- Reads by role go through views: `catalog_current_versions` (the version a caller means: the
  targeted one, else the next) and `catalog_current_releases` (the catalog's highest release,
  released here or received, which the upstream check compares with what the source offers). A
  release is a release however it arrived; how it did is recorded on it (`released_via`,
  `released_from`) and shown, never decided by (ADR 0036, amendment of 2026-10-28).

### 2. Resources are JSON, stored once

- Every resource of every version is a row in `release_resources`
  (`version_id`, `resource_type`, `resource_key`, `resource_id`, `content_hash`, revision and audit
  fields) naming its content in `resource_contents`. Identical content is stored once per tenant, by
  the suite's own storage hash (SHA-256 of the canonical JSON); a draft and the release cut from it
  name the same content.
- Content is an envelope, `{ "resource": <the contract's wire form>, … }`: `resource` is exactly what
  a release ships and fingerprints; suite-only settings sit beside it (a code list's `source`, whose
  credential is a reference into `resource_credentials`). A draft stores the released form; reads
  qualify own-catalog references again where the editor needs them.
- A template's variants are rows of their own (storage type `variant`, `parent_id` = the template's
  identity), each with its own revision and content, so saving one variant touches only that row.
  Storage types (`release_resource_types`) are the wire types plus `variant`; manifest reads go
  through `release_wire_resources`, which has no variants (`ReleaseResourceSqlTest`).
- Binary bytes (image and font files) live once per installation in `asset_content`, by hash;
  content names them through `resource_binaries`. Font faces are the font's own binaries, not image
  assets.
- **Metadata never counts in a hash**: a resource's and a release's `metadata` (producer facts such
  as the rendering-defaults version) are carried and kept, never fingerprinted (contract #103).
- Reads go through one function per type, `catalog_version_resources(type)`, and the
  `current_<type>` views built on it; the read is shaped to plan well before the planner has
  statistics (`ThemePlanStabilityTest`).

### 3. References by alias and key

- Content names other resources as the wire form does: an alias of a catalog the referring catalog
  declares (empty for its own catalog) and a key. `resource_references` indexes them per content;
  the reference walker that fills it is pinned by golden vectors (`fixtures/references/v1.json`).
  A walker change that alters its output for existing content comes with a migration that walks it
  again.
- `resolve_catalog_alias` is the one place an alias is resolved through a version's declarations;
  "used by", "in use" and the graph read through `catalog_version_references`.
- References are not foreign keys: a write checks them, the graph reports what is missing.
- **Releasing pins**: a declaration that pins no release is pinned to its catalog's highest release
  when the version is released, shown in the release dialog; a dependency with no release refuses
  the release, naming it.

### 4. Content is collected, not deleted

Deleting a release, a draft or a catalog removes rows. `ResourceContentCollector`, a single-owner
nightly cluster task, deletes contents and bytes nothing names any more after a grace period
(`epistola.storage.gc.*`), and reports what is left. Document retention is a separate reaper
(`DocumentRetentionReaper`).

### 5. One install core

Every way of receiving a release — file upload, REST, OCI registry, the Exchange, and a URL,
`file:` or `classpath:` source (including the bundled system and demo catalogs) — goes through
`ImportCatalogZip`: the fingerprint is recomputed, binary hashes and relative paths checked,
dependencies required, rendering defaults the installation lacks refused. Without local changes the
next draft fast-forwards to the received release; with local changes the release is recorded beside
it, to be merged (ADR 0032).

### 6. The 1.x upgrade

The upgrade from 1.x is the Flyway chain alone (ADR 0035; the steps are in
[migrations](../migrations.md)). Every authored 1.x catalog keeps its draft — what its authors were
working on — and gets a named draft **"Published in 1.x"** holding what was last published, where it
differs. No release is cut automatically and nothing is deployed: environments start empty, and the
tenant home lists the catalogs to release and deploy. Subscribed 1.x catalogs receive their release
from their source at boot. 1.x releases are not kept; their history is a summary in `app_metadata`.

## Consequences

- One storage, one read path per type, one install core and one version table; the per-type tables,
  `catalog_releases`, the post-startup upgrade runner and the release-hash backfill are gone.
- Starting a next draft copies rows, so releasing is cheap and a new draft costs a copy.
- Every save writes new content; the collector reclaims it. Not keeping every autosave is a later
  improvement.
- Concurrent editing of one catalog by several people is out of scope here (#1135).

## Alternatives considered

Recorded in the superseded ADRs: copying a release into the content tables to read it (0033), views
over two stores (0033), a release as a copy of the draft's rows rather than the frozen row (0036),
`is_next` as a column (0036), an automatic first release on the 1.x upgrade (replaced by
"Published in 1.x", see [migrations](../migrations.md)).
