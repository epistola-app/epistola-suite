# ADR 0036: One table for every catalog version

- **Status:** Proposed (2026-10-26; the last structural step of 2.0's storage, after ADR 0033)
- **Date:** 2026-10-26
- **Deciders:** Epistola team
- **Tags:** catalogs, releases, drafts, storage, database

## Context

A catalog version is one concept. The contract has one id for it from the moment it exists:
"**The draft becomes released** at that version, immutable. Its `releaseId` and its name stay"
(contract ADR 0002). The suite stores it in two tables:

- `catalog_versions` holds the drafts. Since ADR 0032 every row is `kind = 'draft'` and `version`
  is always NULL; "next" is the boolean `is_next`, and a draft that is not next must be named.
- `catalog_releases` holds the releases: number, fingerprint, content hash, notes, manifest snapshot,
  rendering-defaults version, who and when.

That split costs us twice.

- **Two keys for one thing.** A row of `release_resources` names its version by `version_id` (a
  draft) or by `version` (a release), exactly one of the two; `environment_catalog_deployments`
  does the same with `draft_id` or `version`. Six tables reference `catalog_releases` by
  `(catalog_id, version)`, eight reference `catalog_versions` by `version_id`. An inventory on
  `release/2.0` (October 2026) found 93 main Kotlin files naming `catalog_versions`,
  `catalog_releases` or `version_id` (220 occurrences of `version_id` alone), and 23 migrations
  defining views and functions over them. Reading "the version a caller means" has a draft branch
  and a release branch in `CatalogSql.versionAt`, `catalog_current_versions` and
  `CatalogReleaseScoped`.
- **The identity moves at release.** Releasing writes a new `catalog_releases` row and new
  `release_resources` rows keyed by the number (`ReleaseCatalogDraft`, `ReleaseResourceStore.record`),
  gives the release the draft's `version_key`, and gives the draft that stays a fresh key
  (`CatalogReleases.advance`: "its old one now addresses the release"). So the contract's "the draft
  becomes the release" holds for the public id but not for the row, and everything keyed by the
  draft row keeps pointing at the next draft.

Some pointers are not checked at all: `catalogs.released_version`, `released_fingerprint`,
`released_at` and `installed_release_version` are maintained by hand, and a release delete
re-points them in code.

## Decision

**One row per catalog version, draft or released, kept from creation to deletion. Releasing
freezes the row; it copies nothing.** Every table that names a version names that row.

### 1. `catalog_versions`: every version

| Column                                                                                    | Meaning                                                                                             |
| ----------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `tenant_key`, `version_id`                                                                | Primary key: the row's identity, the same before and after release                                  |
| `catalog_id`                                                                              | FK to `catalogs`                                                                                    |
| `version_key`                                                                             | The public id (`CatalogVersionId`, the contract's `releaseId`), unique per catalog, kept on release |
| `state`                                                                                   | `draft` or `released`                                                                               |
| `origin`                                                                                  | `authored` or `installed` (received from an archive, a registry or the Exchange)                    |
| `name`                                                                                    | A draft's name; required for a draft that is not next, kept on release                              |
| `base_version_id`                                                                         | FK to the version this one was made from, replacing the free-text `base_version`                    |
| `revision`, `base_fingerprints`                                                           | Draft bookkeeping; kept, read-only, on release                                                      |
| `default_theme_resource_id`, `presets`                                                    | The version's settings, frozen once released                                                        |
| `version` (+ generated major, minor, patch, label)                                        | The number: NULL for a draft, unique per catalog once set                                           |
| `fingerprint`, `content_hash`, `notes`, `manifest_snapshot`, `rendering_defaults_version` | The release record                                                                                  |
| `released_at`, `released_by`, `released_by_installation`                                  | Who released it, and when                                                                           |
| `created_at`                                                                              |                                                                                                     |

Invariants, as `CHECK`s:

- `state = 'released'` ⇔ `version`, `fingerprint`, `content_hash` and `released_at` are set;
- a draft has no number;
- `origin = 'installed'` ⇒ `state = 'released'` (an installed version is a release; a subscribed
  catalog's next draft is authored);
- `(tenant_key, catalog_id, version)` unique where `version` is set;
  `(tenant_key, catalog_id, version_key)` unique.

### 2. `catalogs`

- `next_version_id`: FK to the draft row that is "next" for the catalog, NULL for none. It replaces
  `is_next`. A trigger or a deferred check keeps it a draft of the same catalog.
- `released_version`, `released_fingerprint`, `released_at` and `installed_release_version` go.
  "The catalog's current release" becomes a query over its released rows (the highest number), in
  one view, so it cannot drift.

### 3. `release_resources`: keyed by the version row only

`version_id` (FK, `ON DELETE CASCADE`), `resource_type`, `resource_key`, `resource_id`,
`content_hash`, `parent_id` (variant rows), `revision`, `created_*`, `updated_*`. The `version`
column and the "exactly one of `version` and `version_id`" check go.

### 4. `catalog_version_dependencies`: one table instead of two

It replaces `catalog_dependencies` (a draft's declarations, which may name another catalog's draft)
and `catalog_release_dependencies` (a release's pins by number).

| Column                | Meaning                                                           |
| --------------------- | ----------------------------------------------------------------- |
| `version_id`          | The version that declares the dependency                          |
| `alias`               | The name it is used under                                         |
| `target_catalog_id`   | The catalog depended on                                           |
| `target_version_id`   | A specific version: a release, or a named draft                   |
| `follows_next`        | The target catalog's next draft; `target_version_id` is then NULL |
| `pinned_content_hash` | For a released target: the hash it is pinned to                   |

A released version may only depend on released versions (`follows_next = false`, target
released), which the freeze guard (6) and a check on release enforce.

### 5. `environment_catalog_deployments`

Per environment and catalog: `target_version_id` (a version row) or `follows_next = true` (the
catalog's next draft, rendered live). This replaces `version` or `draft_id`. See "Open:
deployments across a release" for which of the two a deployed draft becomes.

### 6. Every other reference names the row

`catalog_release_publications`, `catalog_release_merges`, `catalog_draft_merges`,
`resource_ready_marks` and `code_list_refreshes` reference `version_id`, not `(catalog_id,
version)` or a mix of both.

### 7. One place to read a version

`catalog_current_versions` resolves "the version a caller means" to one `version_id`: the targeted
version (`epistola.content_version`, or the release a transaction targets), else
`catalogs.next_version_id`. `catalog_version_resources(type)` and the per-type views build on it
with one key, so the draft and release branches go (ADR 0033's single read place, now keyed once).

### Releasing is freezing

Releasing a draft sets its number, record and `state = 'released'` in place. Its `version_id`,
`version_key` and name stay, so a release made from a draft is the same row, as the contract says.

- **The next draft** is a new row. Starting it copies the released row's `release_resources` rows,
  variant rows included, and its settings and declarations, under the new `version_id`, and sets
  `catalogs.next_version_id`. The cost moves from releasing (today: N new rows) to starting the next
  draft (N copied rows), which is rarer than editing and no rarer than releasing.
- **What follows "next"** follows by catalog, not by row: an editor session, a dependency declared
  with `follows_next`, and a "follows next" deployment see the new draft after the release. A save
  that crosses the release (an editor still holding the old row) is refused, with "this draft was
  released; you are now in the next draft" (contract ADR 0002: "a write through either is refused
  with `409`, never `404`").
- **What names the row** keeps naming it: a document rendered from the draft, a merge, a publication.

### Deleting and installing

- **Deleting a release** removes its row and, by cascade, its `release_resources` rows; the
  collector takes the content nothing names any more (ADR 0033). It is refused while another
  version pins it (`catalog_version_dependencies.target_version_id`), and the user is told where it
  is deployed or used. A draft started from it may stay: `base_version_id` is set to NULL, as
  lineage, not ownership. The current-release view follows on its own.
- **Deleting a draft** is the same, without a number.
- **Installing** a release from elsewhere inserts a row with `state = 'released'`,
  `origin = 'installed'` and the publisher's `version_key`, and its resources.

### Freezing is the application's

The database does not refuse a write to a released version's rows. The writers (`DraftResources`,
`VariantRows`, `CodeListStore`, relocation, settings and declaration commands) refuse it, and a
guard test, like `ReleaseResourceSqlTest`, allows writes to `release_resources` and
`catalog_versions` settings only through code that checks the state.

### Open: deployments across a release

Contract ADR 0002 and 0003 say a deployed draft is followed into its release: "The deployment
follows the draft: releasing it keeps the `releaseId`, so the environment then serves it as
released", and "the deployment stays, an explicit deployment of the released release, logged as
`released`". That is a deployment of the row (`target_version_id`), which this design gives for
free. The proposal to let draft deployments follow the catalog's next draft instead would differ
from the contract. Before (d) is built, one of the two is chosen:

- **As the contract says:** a deployed draft is a deployment of its row; after release it serves the
  release. "Follows next" is then a separate, suite-only option for a test environment that always
  wants the latest draft.
- **Follow next by default:** the contract is amended in the same 2.0 alpha.

## Migration path

One non-rolling step (ADR 0035), in the migration step, after the storage conversions of ADR 0033.
Upgrades from 1.x go through the same chain: the 2.0 upgrade (`V20261018201400`) deletes 1.x
releases, so a 1.x installation arrives with drafts only, and `CatalogUpgradeRunner` cuts first
releases after startup through the new code.

1. **The table.** Add the release columns, `state` and `origin` to `catalog_versions`; insert one
   released row per `catalog_releases` row, keeping its `version_key` and number (SQL); set
   `catalogs.next_version_id` from `is_next`; set `base_version_id` by number where the base still
   exists.
2. **The re-keys.** For each released row, set `release_resources.version_id` from `(catalog_id,
   version)`, then drop `version` and its check; the same for deployments, publications, merges and
   ready marks.
3. **The dependencies.** Fill `catalog_version_dependencies` from both tables: draft declarations
   by `version_id` (with `follows_next` where they named the next draft), release pins by number.
4. **The pointers.** Drop the four columns on `catalogs` and replace their readers with the view.
5. **The old tables.** Drop `catalog_releases`, `catalog_release_dependencies`, `is_next`.

All of it is SQL; a frozen code migration is used only if a step turns out to need the suite's
logic, and then under ADR 0035.

## Build order

Each step is its own pull request, with its own tests and a count of the query occurrences it
removes, on top of the previous one.

- **(a) The table and identity.** Columns, invariants, the migration of `catalog_releases` rows,
  `catalogs.next_version_id`. Releasing freezes the row; starting a draft copies. Reads still work
  through the old keys, mirrored. Removes the `advance` key swap.
- **(b) The re-keys.** `release_resources` and the five other tables to `version_id`. Removes the
  release branch of `versionAt`, `catalog_current_versions` and the `COALESCE(dv.version_key,
  r.version_key)` joins.
- **(c) The dependencies.** One table; the release-pin and draft-declaration code paths become one.
- **(d) Deployments and "follow next".** After the open point is decided. Removes the
  `version`/`draft_id` pair and its two joins.
- **(e) Removing the old.** `catalog_releases`, `catalog_release_dependencies`, `is_next`, the
  `catalogs` pointers and their readers. The guard test refuses new SQL on them.
- **(f) The freeze guard.** The writer checks and the guard test.

## Consequences

- One key for a version everywhere. The query occurrences over versions shrink to the read place,
  the writers and the guard's allowlist; the target is to name them in the guard and keep it so.
- The contract's identity holds literally: the draft's row, id and name become the release.
- The cost of a release moves to starting the next draft. Releases are rarer than edits, so it is
  acceptable, but `release_resources` holds one copy more per open next draft, and the plan tests
  (`ThemePlanStabilityTest`) must cover the new key on fresh tables.
- Anything that cached a draft's `version_id` across a release must follow by catalog; the editor
  shows the crossing instead of failing silently.
- Freezing rests on the application and its guard test; a writer that forgets the check is caught
  by the guard, not by the database.

## Alternatives considered

- **Keep two tables.** No migration, but the dual keys, the two read branches and the unchecked
  pointers stay, and every new feature pays for them again. Rejected: it is the "wow" this ends.
- **Release as a copy (today's way).** The draft row stays and a release row is written beside it.
  It keeps editors and deployments on the draft for free, but the identity moves (the contract's id
  is handed from row to row) and every release writes N rows. Rejected: the copy belongs to starting
  a draft, which is where the contract says a new version begins.
- **`is_next` as a column on the version.** What exists today. "Next" is a property of the catalog
  (at most one, possibly none), so a nullable FK on `catalogs` states it without a partial unique
  index and moves with one update. Rejected.

## Related

- [ADR 0032](0032-catalog-sources-releases-and-merging.md): catalogs are drafts and releases.
- [ADR 0033](0033-reading-any-release.md): one read place per type, the collector.
- [ADR 0034](0034-content-addressed-releases-and-named-pointers.md): releases by content hash.
- [ADR 0035](0035-data-migrations-and-skipping-upgrades.md): how the migration is written.
- Contract ADR 0002 (drafts and released versions) and ADR 0003 (deployments).
