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
namespaced by a derived **scope** — never stored, always derived from the asset's
`sensitive` flag (`CASE WHEN sensitive THEN tenant_key ELSE 'global' END`, see
`assetContentScope`):

- **`global`** (default) — non-sensitive assets (branding, images, fonts, and the
  bundled system catalog) dedup **installation-wide**: identical bytes — a shared font, a
  logo two tenants happen to reuse, the system-badge across every tenant — are stored
  **once**. This is the headline saving.
- **tenant key** — assets marked **sensitive** are stored in isolation per tenant, so
  there's no cross-tenant existence side-channel (a tenant can't infer another uploaded
  the same bytes) and physical erasure is clean. Sensitive assets still dedup against each
  other within one tenant.

The `sensitive` flag is honoured by the backend today; surfacing it on the UI, REST, and
catalog-exchange formats is tracked in
[#751](https://github.com/epistola-app/epistola-suite/issues/751) (all assets are
non-sensitive / global until then).

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

Blobs used to share a single `content_store` table. Moving them to the two new stores is
a **transitional layer** deliberately grouped in one package —
`storage/backfill/` — so it can be deleted wholesale later (tracked in
[#742](https://github.com/epistola-app/epistola-suite/issues/742)). It has two parts:

- **`ContentBackfillRunner`** — a **single-owner background cluster task** (not on the
  startup path, so it never races readiness on a large install), batched, resumable, and
  idempotent. It moves blobs forward and records a `content-backfill.completed` marker in
  `app_metadata` after a full pass, so later occurrences no-op. During a rolling deploy it
  only ever _executes_ on an upgraded node that carries its handler — an old node that
  claims an occurrence defers it rather than running or deleting it (see
  [`cluster-resilience.md`](cluster-resilience.md#4-version-skew-during-rolling-deploys)):
  - **Documents (PostgreSQL only)** → `document_content`, bucketed into the right monthly
    partition by the owning document's `created_at`. Blobs whose `documents` row is already
    gone are pre-existing retention orphans — discarded, not carried forward. On
    S3/filesystem, document bytes stay in place; only the reclaim mechanism changed.
  - **Assets (all backends)** → `asset_content`, hashing + deduping + stamping
    `assets.content_hash`.
- **`LegacyBlobFallback`** — because the backfill runs in the background, a document or
  asset may be requested before it's migrated (or on a node that isn't running it). The
  read paths fall back to `content_store` for a document missing from `document_content`,
  or an asset whose `content_hash` is still NULL — so serving stays correct throughout the
  migration window on every node.

The legacy `content_store` table is retained one release as a safety net, then dropped
once cutover is verified — at which point the `storage/backfill/` package and the legacy
`ContentStore` are removed and `assets.content_hash` becomes `NOT NULL` (#742).

### Rolling-upgrade caveats

During the mixed-version window of a rolling upgrade, old (pre-#738) nodes still write to
`content_store`. Two consequences, both bounded:

- **Stragglers** written by an old node after the backfill's completion marker is set are
  served correctly via `LegacyBlobFallback`, but would be missed by a naive drop. The
  `content_store` drop (#742) therefore runs a **final catch-up pass and verifies actual
  drain** — it does not trust the marker alone.
- **Transient stale asset reads:** an old `ImportAsset` re-importing an _already-migrated_
  asset updates the legacy `content_store` bytes but leaves `assets.content_hash` pointing
  at the old CAS blob, so a new reader briefly serves the previous bytes (it only falls back
  when `content_hash IS NULL`). This resolves once the old nodes drain; avoid catalog
  re-imports mid-upgrade.

Backups skip a tenant while it still has un-migrated assets (`content_hash IS NULL`), rather
than write an archive missing those bytes; they resume automatically once the backfill drains
the tenant.
