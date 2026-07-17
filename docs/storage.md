# Blob storage

Epistola stores two kinds of binary content with very different lifecycles, in two
purpose-built stores (issue #738). This split fixes an unbounded blob leak and adds
deduplication.

## Two stores

|           | Document content (ephemeral)                | Asset content (permanent)                   |
| --------- | ------------------------------------------- | ------------------------------------------- |
| What      | Generated PDFs                              | Logos, images, font files                   |
| Port      | `DocumentContentStore` (pluggable)          | `AssetContentStore` (always PostgreSQL)     |
| Keyed by  | `documents/{tenant}/{document}`             | `(scope, sha256)` — content-addressable     |
| Lifecycle | Ages out with the document retention window | Keep-forever                                |
| Reclaim   | Per-backend (below)                         | Ref-aware: mark-and-sweep by the reaper     |
| Dedup     | No                                          | Yes — identical bytes stored once per scope |

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
  `ContentRetentionMaintainer`; the reaper drives an age sweep of `documents/**` by
  modification time.

## Asset dedup and scope

Asset blobs are content-addressable (SHA-256, reusing `sha256Hex`). Deduplication is
namespaced by a derived **scope** — a privacy boundary, never stored, always derived
from the owning asset's `catalog_key` (`CASE WHEN catalog_key = 'system' THEN 'system'
ELSE tenant_key END`, see `assetContentScope`):

- **`system`** — bundled / system-catalog assets (fonts, demo images) dedup **globally**:
  the system-badge, a bundled font, etc. are stored **once per installation** regardless
  of how many tenants install the system catalog. This is the headline saving.
- **tenant key** — user uploads dedup only **within the tenant**, avoiding a
  cross-tenant existence side-channel and keeping "delete all my data" clean.

`assets.content_hash` points into `asset_content`. Writes (`UploadAsset`, `ImportAsset`)
hash + `putIfAbsent`; `GetAssetContent` reads by `(scope, hash)`. `DeleteAsset` deletes
only the `assets` row — a blob may back many assets, so bytes are reclaimed later by the
reaper.

## The content reaper

`ContentReaper` is a single-owner daily cluster task
(`core.content-reaper`, `epistola.storage.reaper.cron`) that:

1. **Mark-and-sweeps** `asset_content` blobs older than a grace window
   (`epistola.storage.reaper.asset-grace-minutes`, default 60) that no `assets` row
   references. The grace window protects an in-flight upload (blob written, row not yet
   inserted); a wrongly-swept blob self-heals on the next `putIfAbsent`.
2. **Drives** each `ContentRetentionMaintainer` (the filesystem sweep).
3. Publishes `epistola.storage.orphaned_blobs{namespace=asset}` (see
   [`docs/metrics.md`](metrics.md)).

Disable with `epistola.storage.reaper.enabled=false`.

## Migration from the legacy `content_store`

Blobs used to share a single `content_store` table. `ContentBackfillRunner` is a
one-time, advisory-locked, batched, idempotent startup runner that moves them forward:

- **Documents (PostgreSQL only)** → `document_content`, bucketed into the right monthly
  partition by the owning document's `created_at`. Blobs whose `documents` row is already
  gone are pre-existing retention orphans — discarded, not carried forward. On
  S3/filesystem, document bytes stay in place; only the reclaim mechanism changed.
- **Assets (all backends)** → `asset_content`, hashing + deduping + stamping
  `assets.content_hash`.

The legacy `content_store` table is retained one release as a safety net, then dropped
once cutover is verified (`assets.content_hash` becomes `NOT NULL`).
