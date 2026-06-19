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
  (`flyway_schema_history`). Same stamp always restores. A _different_ stamp
  restores only when every migration crossed between the backup and the running
  schema is declared compatible in `schema-backup-compatibility.yaml` (default
  deny) — see [Schema compatibility](#schema-compatibility) below. Restore also
  verifies each backed-up table's column set still matches (the structural
  backstop), regardless of the stamp.
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

## Schema compatibility

A backup taken at one schema can restore into a **different** schema only when
every migration crossed between them is explicitly declared safe — otherwise the
restore is refused (default deny). The declarations live in
`modules/epistola-support-backups/src/main/resources/schema-backup-compatibility.yaml`,
one entry per migration with two directional flags:

```yaml
migrations:
  - version: "20260618204750"
    backward: true # an OLDER backup may restore into a schema that HAS this migration
    forward: true # a NEWER backup may restore into a schema that does NOT have it
    reason: "Data-only migration; no structural change to backed-up tables."
```

The two directions read the flags from different places, because a running app's
schema head equals its own code's migration head — so it can only _see_ the
migrations up to itself:

- **Backward** (restoring an older backup after an upgrade — the common case): the
  live (newer) app knows the crossed migrations and reads their `backward` flags
  straight from this file.
- **Forward** (restoring a newer backup after a downgrade): the live (older) app
  is blind to the newer migrations, so it reads the `forward` flags the backup
  **snapshotted into its manifest** (`appliedMigrations`) at build time. Backups in
  the legacy v1 format don't carry these, so they can't be forward-restored.

`validateColumns` (every backed-up table structurally identical) still runs in both
directions as the automatic backstop — the flags only relax the _stamp_, never a
structural change.

**Maintaining the file:** when a migration is safe to cross, add an entry with the
right flag(s); leave a restore-breaking migration out (it becomes the compatibility
boundary). Every listed version must be a real migration —
`SchemaBackupCompatibilityFileTest` fails CI on a typo. There is no manual "restore
anyway" override; compatibility is decided solely by the file + the structural check.

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

### Why an explicit list (and when to change it)

The `INCLUDE` / `DENY_TENANT_TABLES` lists are the **single, hand-maintained
source of truth** for what leaves the database in a backup. That is deliberate:
"what's in a backup" is a data-fidelity and security decision, and keeping it
auditable in one place beats scattering it across migrations (e.g. as per-table
SQL comments, which can't be reviewed at a glance, are easy to typo, and get lost
in a migration consolidation). The drift test removes the only real downside —
forgetting to update the list — by failing the build on any unclassified table.

A few INCLUDE entries (the `feedback*` tables) are owned by other feature modules
and referenced by name only (no compile dependency); a rename trips the drift test
too. This is fine while **one** external module contributes tenant-scoped tables.
When a **second** one does, evolve the central lists into a small per-module
contribution SPI — each module declares its own include/exclude and the topology
aggregates them — mirroring the existing `NavContributor` / `FooterContributor`
pattern. Until then it would be premature; keep the explicit list.
