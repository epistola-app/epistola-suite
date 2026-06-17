# Tenant Backup & Restore

A **tenant backup** is a faithful, full-fidelity, identity-preserving copy of one
tenant's authoring data. It exists to **correct mistakes** — undo a bad edit, a
wrong delete, a botched bulk change — not for disaster recovery or cross-version
rollback.

This is deliberately **distinct from catalog export/import** (`exchange.md`).
Export is a _publishing_ format: it keeps only the latest published version of
each resource and renumbers versions on import. A backup keeps **everything**,
verbatim.

## What a backup contains

A backup captures every **tenant-scoped authoring table**, classified in
`TenantTableTopology.INCLUDE`:

- `tenants` (settings; updated in place, never deleted), `catalogs`,
  `catalog_releases`
- templates and their full history: `document_templates`, `template_variants`,
  `template_versions` (all of draft/published/archived, **exact version numbers
  preserved**), `contract_versions`
- `stencils` + `stencil_versions`, `themes`, `code_lists` + `code_list_entries`,
  `fonts` + `font_variants`, `assets` (+ their `content_store` blobs),
  `variant_attribute_definitions`
- `environments` + `environment_activations`, `api_keys` (SHA-256 hashes, dumped
  verbatim), `feature_toggles`
- the feedback feature tables when present (`feedback*`)

It deliberately **excludes** (`DENY_TENANT_TABLES`): generated documents
(`documents`, `document_generation_requests`, `document_generation_batches`),
the append-only collect feed and cursors (`generation_results`,
`consumer_*`) — which must survive and stay monotonic for external consumers —
and audit/runtime/membership tables. These are never read or written by
backup/restore.

## How it works (the `tenantbackup/` primitive)

`BuildTenantBackup` / `RestoreTenantBackup` (in `epistola-support-backups`,
package `app.epistola.suite.tenantbackup`) are **reflective**: they discover the table
set, FK insert order, primary keys, and per-column types from `information_schema`
at runtime. A schema migration that adds a column — or a whole table — is picked
up automatically; only genuine special cases are hand-coded.

- **Type fidelity** — complex columns (jsonb, uuid, timestamptz, arrays, numeric,
  bytea) round-trip through a canonical `::text` / `encode()` projection on dump
  and the matching cast on re-bind. Credential `enc:v1:` columns are read raw
  (no decryption) and ride through verbatim.
- **Schema-version gate** — the artifact stamps the Flyway schema head
  (`flyway_schema_history`). Restore refuses an artifact whose stamp ≠ the running
  schema, and also verifies each table's column set still matches. A backup is a
  **same-schema undo**, never a migration.
- **Encryption at rest** — the whole archive is wrapped with the existing
  `CredentialCipher` keyset (`epistola.encryption.*`, AES-256-GCM, key rotation).
  Restore needs the same keyset present — the same constraint that already governs
  the live DB's encrypted credential columns. See [`encryption.md`](encryption.md).

### Restore is a merge, not a wipe

This is the key property. Restore runs in one transaction and:

1. **Upserts** every backed-up row (`INSERT … ON CONFLICT (pk) DO UPDATE`),
   parent→child in FK order. Existing primary keys are **never deleted**, so the
   `documents` / `generation` rows that FK-cascade off an unchanged
   `template_versions` row are untouched — **generated history survives**.
2. **Deletes only the live rows absent from the backup** (post-backup
   work-in-progress), child→parent. Their cascade onto documents is the _intended_
   removal of work that never shipped.

Because version numbers are preserved exactly and rows are upserted (not
renumbered), any identifier an external consumer has pinned — a document's
`version_key`, a collect-feed cursor, "generate template X version 37" — stays
valid across a restore.

Special cases the merge handles: the `tenants`↔`themes` circular FK (the default
theme is nulled during the merge and re-applied at the end), the deferred
`font_variants`→`assets` FK, the RESTRICT `variant_attribute_definitions`→
`code_lists` edge, and the `content_store` asset blobs.

## Storage and the Backups UI

Backups are stored **locally** in the `tenant_backups` table (the artifact bytes
plus metadata) via the `TenantBackupStore` port — a hub transport can replace it
later without touching the scheduler or UI. The Support → Backups page lists a
tenant's backups, runs an on-demand backup, and restores one; `BackupScheduler`
runs a daily backup per tenant with the feature available, keeping the 30 newest
(`epistola.support.backups.retention`). Builds are de-duplicated by content
fingerprint, so an unchanged tenant stores nothing new.

The **Upgrading** feature is independent: it keeps using the catalog _export_
snapshot (`TenantSnapshotSyncService`) for its hub compatibility checks, so
backups and upgrading toggle separately.

## Adding a tenant-scoped table

Every tenant-scoped table must be classified INCLUDE or DENY in
`TenantTableTopology`. A new migration that adds one will fail
`TenantTableTopologyDriftIntegrationTest` until you decide — back it up (INCLUDE)
or exclude it (DENY). See [`migrations.md`](migrations.md).
