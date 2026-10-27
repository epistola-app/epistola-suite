# Blob storage

Epistola stores two kinds of binary content with very different lifecycles, in two
purpose-built stores (issue #738). This split fixes an unbounded blob leak and adds
deduplication.

## Two stores

|           | Document content (ephemeral)                | Asset content (permanent)                 |
| --------- | ------------------------------------------- | ----------------------------------------- |
| What      | Generated PDFs                              | Logos, images, font files                 |
| Port      | `DocumentContentStore` (pluggable)          | `AssetContentStore` (always PostgreSQL)   |
| Keyed by  | `documents/{tenant}/{document}`             | `sha256` — content-addressable            |
| Lifecycle | Ages out with the document retention window | Kept while a content holds it             |
| Reclaim   | Per-backend (below)                         | Ref-aware: swept by the content collector |
| Dedup     | No                                          | Yes — stored once per installation        |

The pluggable backend (`epistola.storage.backend` = `POSTGRES` (default) / `S3` /
`FILESYSTEM` / `MEMORY`) now applies **only to document content**. Asset content is
always in PostgreSQL, so the whole installation dedups assets uniformly and a tenant's
asset bytes are physically theirs (clean erasure).

> **Backend support status.** `POSTGRES` is the only tested, production-supported
> document backend. `S3` and `FILESYSTEM` are **alpha** — wired end-to-end but not
> exercised by CI or run in production, so their retention paths (the S3 lifecycle rule
> and the filesystem age sweep) are unvalidated; selecting one logs a startup warning.
> `MEMORY` is test-only. Everything below describing S3/filesystem documents how they
> are _intended_ to work, not a validated guarantee.

## Document retention is per-backend

A document blob is written with its owning document's `created_at` and reclaimed the
way each backend does best:

- **PostgreSQL** — `document_content` is `RANGE`-partitioned by `created_at` on the
  **same** retention window as `documents` (`epistola.partitions.retention-months`,
  default 3). `PartitionMaintenanceScheduler` drops `document_content_YYYY_MM` in
  lockstep with `documents_YYYY_MM` — an O(1) `DROP TABLE`, no per-row `DELETE`, no
  BYTEA bloat. This is the fix for the original leak: a partition drop on `documents`
  used to leave the matching blobs in an unpartitioned `content_store` orphaned forever.
- **S3** — `S3DocumentRetentionInitializer` reconciles a bucket **lifecycle rule** at
  startup that expires the `documents/` prefix after the retention window
  (`epistola.storage.s3.document-retention-days`, default `retention-months × 31`).
  Assets are never in the bucket, so the rule can never touch permanent data. It
  **amends** the bucket configuration — reads the existing rules and merges ours in by
  id — so any other lifecycle rules on the bucket are preserved. Set
  `epistola.storage.s3.manage-document-lifecycle=false` to opt out entirely and manage
  the bucket lifecycle yourself.
- **Filesystem** — `FilesystemDocumentContentStore` implements
  `ContentRetentionMaintainer`; the document retention task drives an age sweep of `documents/**` by
  modification time.

## Image and font bytes

The bytes of images and font faces are content-addressable (SHA-256, `sha256Hex`) and stored
**once per installation** in `asset_content`: identical bytes — a shared font, a logo two tenants
happen to reuse, the system badge and the bundled system fonts across every tenant — are one row.
An image or a font is a stored content (ADR 0033) naming its bytes by hash, and the content links
to them through `resource_binaries`, a draft's as a release's; nothing else points at them.

Writes (`UploadAsset`, `ImportAsset`, `ImportFont`) store the bytes first, when they are not there
yet, then the content; reads (`GetAssetContent`, the font resolvers) read by hash. Deleting an image
or a font removes its draft row only: bytes many contents may name are reclaimed later, once the
content collector has taken the contents nothing names, by that same collector.

There is no per-tenant scope any more. 1.x let an asset be marked `sensitive`, which kept its bytes
apart per tenant (#751, never surfaced on the UI, REST or catalog formats); 2.0 drops the flag: the
upgrade moves such bytes into the one store, deduplicated (`V20261101000300`), and the REST upload's
`sensitive` field is dropped from the contract.

## Document retention

`DocumentRetentionReaper` is a single-owner daily cluster task (`core.document-retention`,
`epistola.storage.reaper.cron`, default `0 30 3 * * ?`) that drives each `ContentRetentionMaintainer`
with `epistola.partitions.retention-months`: the filesystem document backend's age sweep. PostgreSQL
reclaims documents by dropping partitions and S3 by its lifecycle rule, so they contribute none.
Disable with `epistola.storage.reaper.enabled=false`.

It no longer touches asset bytes: those are the content collector's, below.

## The content collector

Release content is stored once per tenant by its content hash (ADR 0033): `release_resources`
(one row per resource of a release) points at `resource_contents` through a foreign key, with
`resource_binaries` naming the bytes in `asset_content` a content needs and `resource_references`
what it refers to. Rows go at once; content nothing points at any more is collected later by
`ResourceContentCollector`, a single-owner cluster task (`core.resource-content-collector`):

1. Per tenant, in batches of `epistola.storage.gc.batch-size` (default 500), each its own
   transaction with `epistola.storage.gc.pause` between them (default 200 ms), it deletes
   `resource_contents` that no `release_resources` row names (a template's variants are rows of
   their own, so a row is the only holder), stored longer ago than `epistola.storage.gc.grace` (default 1 day).
   A batch selects `FOR UPDATE SKIP LOCKED`; storing content again touches and locks it
   (`stored_at`), so a save that reuses content is skipped, and the foreign key refuses to delete
   content a row points at. Its `resource_binaries` and `resource_references` cascade.
2. Then it deletes `asset_content` that no content's binaries hold (`resource_binaries`), older than
   the same grace period, in batches. It is the only place bytes are deleted. The grace period counts
   from the blob's last write: writing bytes that are stored already touches them (`putIfAbsent`,
   database time), so an upload that reuses an old blob is never swept from under it.
3. It publishes `epistola.storage.orphaned_contents` and `epistola.storage.orphaned_blobs{namespace=asset}`:
   orphaned content and bytes past the grace period left after the run, zero when it did its job.

It runs on `epistola.storage.gc.cron` (default `0 0 2 * * ?`, nightly at 02:00); disable the
schedule with `epistola.storage.gc.enabled=false`. An operator with diagnostics rights starts it
by hand for one tenant from **Operations → Cluster → Stored content → Collect now** (the command
`CollectResourceContents`), after removing many releases, say. Deleting a release or a catalog removes its rows only; the
content only it held is collected here, past the grace period, like any other orphan.

A draft's resources of every type (themes, code lists, attributes, stencils, fonts, images and templates) are `release_resources` rows too,
keyed by the draft (`version_id`) instead of a release number, and their content is stored the same
way when they are saved ([catalog versioning](catalog-versioning.md#one-storage-for-drafts-and-releases)).
A draft row holds its content as a release row does; content a save left behind is an orphan the
collector takes after the grace period. A template is the busiest case: each variant is a row of
its own (storage type `variant`, naming its template's row), so each save of a variant's model stores
that variant's content alone, and an editing session leaves one content per distinct save
(`TemplateReadsPerfTest` measures it) until the collector runs. Because the table holds variant rows
beside the wire resources, what lists, counts or fingerprints entries reads `release_wire_resources`
(`ReleaseResourceSqlTest`); `release_resource_types` lists what a row may be. Not keeping every
autosave is a later improvement. A font's faces are its binaries: its content links to their bytes
(`resource_binaries`), a draft's as a release's, so a face's file is kept while anything names it;
there is no asset row for it. The collector also deletes the credentials (`resource_credentials`,
a code list source's secret) that no content names any more, past the same grace period.

## Migration from the legacy `content_store`

Blobs used to share a single `content_store` table (1.x). 1.x moved them to the two stores above in
the background (`ContentBackfillRunner`, #738); an installation that upgrades to 2.0 before that job
finished has the rest moved in the migration step instead (`V20261101000500`, ADR 0035, before the fonts
are converted, so a face whose bytes were still there joins its font), and the table is dropped (`V20261101001400`, #742):

- **Documents** → `document_content`, into the monthly partition of the owning document's
  `created_at`, one month at a time.
- **Assets** → `asset_content`, hashed (SHA-256), under the shared scope or the tenant's for a
  sensitive one, with `assets.content_hash` stamped. An asset whose legacy bytes are missing is
  logged and left without content: the migration never refuses.
- **Font faces**: a 1.x face that names no hash takes its asset's, which this stamps, so a face whose
  bytes were still in `content_store` joins its font when the fonts are converted next.

The migration runs outside Flyway's single transaction, a batch per transaction, and is idempotent:
what committed before a failure is kept, and running it again does only the rest. Flyway records no
history row for a non-transactional migration that fails, so the next run of the migration step runs
it again (`FailedMigrationRerunIT`). Rows left in `content_store` after it named nothing that exists
any more (a document deleted before its blob moved); their count is logged and they go with the
table. 1.x kept the legacy store in the database, so nothing is read from S3 or a filesystem.
