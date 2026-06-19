# ADR 0007: At-Rest Resource Migration

- **Status:** Proposed
- **Date:** 2026-06-19
- **Deciders:** Epistola team
- **Tags:** catalog, versioning, migration, database, at-rest, wire-format

## Context

[ADR 0006](0006-catalog-wire-format-migrations.md) adds an EF-style migration
chain for the catalog **wire format** — an old export is mechanically upgraded
to the current shape **at the import boundary**, before it is bound and
installed. That ADR explicitly scopes only the exchange format: "this ADR
scopes only the catalog exchange wire format."

This ADR records the **adjacent, unbuilt half**: resources that are already
**stored in the database** are never migrated when the application is upgraded
against an existing database. The wire migrator handles data _in transit_; it
does nothing for data _at rest_.

As the suite moves toward production, the pre-release "just wipe it" stance
(CLAUDE.md) stops being acceptable. A new app version booting against an
existing production database will read rows written by an older app version.
Three facts make this a real gap rather than a theoretical one:

- **There is no per-resource format version.** The wire `schemaVersion` is
  dropped at install — `CatalogContentBuilder` stamps it on export, but
  `InstallFromCatalog` / `ProtocolMapper` never persist it. The only
  version the database tracks is Flyway's `flyway_schema_history`, which is a
  **DDL** high-water mark, not a content/format version. You cannot ask a
  stored template "what format are you."
- **Authored / user content has no source to re-fetch.** Bundled demo/system
  catalogs are reconciled at boot (fingerprint gate → import path → wire
  migrator), and external SUBSCRIBED catalogs can be re-fetched on demand. But
  AUTHORED catalogs and user-edited resources have **no source URL**. Only an
  **in-place** migration can reach them — a re-import pass fundamentally
  cannot.
- **Wire version and storage format are decoupled.** The wire DTOs
  (`epistola-model`) and the domain storage are separated by an
  anti-corruption layer (`ProtocolMapper`). A wire bump is absorbed at the
  import boundary and does **not** by itself touch stored rows. The two evolve
  on independent axes.

### Decision drivers

- **Survive version skew at deploy.** A new app version must read and, where
  the format moved, upgrade resources written by an older version of itself —
  including authored content with no upstream source.
- **One migration mechanism, not two.** We do not want a wire-import migration
  _and_ a separate, parallel hand-written Flyway-data migration for the same
  logical format change. A single content-format change should be expressed
  once.
- **The typed model is always current.** Same constraint as ADR 0006: an
  old-shaped JSON blob cannot bind to the current model, so any reshape must
  run on the untyped tree before binding/use.
- **Keep DDL where it belongs.** Structural schema evolution (tables, columns,
  constraints) is Flyway's job and stays there; this is about **content
  reshape** of JSONB blobs, a different axis.

## Analysis

### Three buckets (wire vs database)

Comparing the wire format against the database schema, every field falls into
one of three buckets — and only one of them is a content-migration concern:

- **A — transport-only.** `schemaVersion`, `ResourceEntry.detailUrl`,
  `AssetResource.contentUrl`, `release.fingerprint`, `includes`. Exists only
  in transit; absent at rest. Not a migration concern.
- **B — relational scaffolding (DB-only).** `tenant_key`, `catalog_key`,
  `status` (draft/published/archived), sequential version ids,
  `installed_*` / `released_*`, audit columns. No wire equivalent; owned by
  **Flyway DDL**. Not part of any content chain.
- **C — shared content core (the JSONB ASTs).** The template document, theme
  styles, stencil content, data contract. Essentially the same JSON on the
  wire and at rest — **the only genuinely migratable surface.**

### Blob classification (import direction, wire → DB)

Traced through `ProtocolMapper` and the `Import*` handlers, the bucket-C blobs
split into three groups:

**Stored byte-identical (7) — already canonical, fit a shared chain unchanged:**

| Blob                 | DB column                                      | Path                      |
| -------------------- | ---------------------------------------------- | ------------------------- |
| `templateModel`      | `template_versions.template_model`             | passthrough               |
| `documentStyles`     | `themes.document_styles`                       | null-filter only          |
| `pageSettings`       | `themes.page_settings`                         | passthrough               |
| stencil `content`    | `stencil_versions.content`                     | passthrough               |
| `dataModel`          | `contract_versions.data_model`                 | `valueToTree` round-trip  |
| variant `attributes` | `template_variants.attributes`                 | passthrough (null → `{}`) |
| `allowedValues`      | `variant_attribute_definitions.allowed_values` | passthrough               |

**Reshaped on import (stored ≠ wire) — must be made canonical first:**

- `blockStylePresets` → `themes.block_style_presets`: type-coerced through a
  custom `BlockStylePresetsSerializer` (`ProtocolMapper.mapToBlockStylePresets`,
  `convertValue`), so the stored form is a re-serialized, schema-validated
  shape rather than the raw wire map.
- `dataExamples` → `contract_versions.data_examples`: each entry gains a
  **generated UUID `id`** at install (`InstallFromCatalog.installTemplate`);
  the wire carries no `id`, so stored ≠ wire by construction.

**Relationally decomposed — outside the content chain (Flyway DDL):**
code-list `entries` → `code_list_entries`; font `variants` → `font_variants`;
attribute `codeListBinding` → `code_list_catalog_key` + `code_list_slug`.

**Notes.** FontRef / CodeListBindingRef references _inside_ the blobs are
preserved verbatim on import (good for identity). Stencil-pin renumbering
(`ImportTemplates.kt`) can rewrite `templateModel`'s `props.version` on
conflict — a conditional transform outside any version chain. Contract `schema`
is not populated by catalog import (only `data_model` / `data_examples`).

## Decision

Unify on a single content migration driven at both boundaries — the import
boundary (ADR 0006) and a new at-rest (startup) boundary — sharing one chain.
The framework is built now; no real migration step ships until the first
non-additive content change.

1. **Version with one global counter, not per-row.** Modelled on Flyway / EF:
   the app code is always current, so at any moment every stored blob is at one
   content version. A single `app_metadata` key
   (`catalog.content.schema_version`) records it — no per-row `schema_version`
   column, no DDL. (`flyway_schema_history` can't serve here: it tracks DDL, not
   content format.)
2. **Reuse the `CatalogSchemaMigration` SPI** with a blob-level step
   `migrateContentBlob(blobType, JsonNode, ctx)` keyed by **content shape**
   (`ContentBlobType`), so one step migrates a shape wherever it lives — e.g. a
   `TEMPLATE_DOCUMENT` step covers both `template_model` and stencil `content`,
   and (later) the wire path too. The import-boundary driver from ADR 0006
   already exists; `AtRestContentMigrator` is the new startup driver.
3. **Migrate-all-when-behind, atomically.** On boot, if the counter is below
   current, run the chain over **every** bucket-C blob and bump the counter in
   one transaction — a failure rolls back and the next boot retries, no
   double-apply. Because the counter cannot tell a freshly-written current blob
   from a lagging one, the pass runs **before** any bootstrap that writes
   current-shape content (`DemoLoader`, `SystemCatalogBootstrap`). An absent
   counter (fresh / pre-tracking DB) adopts current without migrating.
4. **No canonicalization needed.** Only `blockStylePresets` and `dataExamples`
   diverge between wire and storage today, and field-addressed `JsonNode`
   transforms tolerate that (key order, an extra `dataExamples.id`); making
   `dataExamples` ids deterministic would also break editor selection. Revisit
   only if byte-level wire/DB parity is later required for another reason.

This makes the invariant the team wants — _"everything stored is at the current
content version, so a version bump migrates every resource at startup"_ —
enforceable through one counter and one shared chain.

### Considered alternatives

- **Per-row `schema_version` column on each carrier.** More granular
  (resumable per row, tolerant of heterogeneous versions), but the homogeneity
  the global counter assumes holds in practice (imports normalise to current,
  the app writes current), and a transaction gives the same partial-failure
  safety — so the per-row column was dropped as unnecessary DDL.
- **Stance A — two axes, shared helpers.** Keep wire migration and at-rest
  (Flyway-data) migration as separate mechanisms, factoring each reshape into one
  pure `JsonNode → JsonNode` helper invoked from both. Shares logic but does not
  collapse the two mechanisms. Kept as the fallback if the single-chain model
  strains.

## Consequences

### Positive

- A new app version against an existing database can upgrade stored resources
  — including authored content with no upstream — through the same chain that
  already upgrades imports.
- A single content-format change is expressed once, not duplicated across a
  wire migration and a Flyway data script.
- The migratable surface is small and mostly already canonical (7 of the
  bucket-C blobs need no work), so the eventual change is bounded.

### Negative

- A version bump rewrites **every** bucket-C row, not just changed ones — the
  global counter can't filter at row granularity. Acceptable at current scale
  (one pass per bump, at boot); a per-row column would be the escape hatch if
  content volume ever makes this too costly.
- Two ordering assumptions the counter relies on: the pass must run before any
  current-content writer (`DemoLoader`, `SystemCatalogBootstrap`), and an absent
  counter is treated as current. Both are safe while imports normalise to
  current and the feature ships before the first content bump, but a restore of
  an older snapshot must reset the counter to the snapshot's version.

### Neutral / follow-ups

- **No change ships with this ADR.** While `BASELINE == CURRENT` the wire chain
  is empty and nothing at rest needs migrating; this records the design so the
  first real content change has a path instead of a wipe.
- **Precedent in the codebase.** `contract_versions` already does domain-level
  content versioning (sequential id, draft/published, `SchemaCompatibilityChecker`)
  — a pattern to mirror for at-rest resource versioning.
- **Snapshot format.** `RestoreTenantSnapshot` / `SNAPSHOT_SCHEMA_VERSION` is
  flagged by ADR 0006 as a candidate for the same migrator shape; an at-rest
  chain and a snapshot chain likely want to converge.

## Implementation references

- Wire-format migration this builds on: ADR 0006,
  `app.epistola.suite.catalog.migrations` (`CatalogSchemaMigrator`,
  `CatalogSchemaMigration`, `MigrationContext`).
- Anti-corruption layer where identity vs reshape originates:
  `ProtocolMapper` and the `Import*` handlers (`InstallFromCatalog`,
  `ImportTemplates`, `ImportTheme`, `ImportStencil`).
- Bucket-C carriers: `themes`, `template_versions`, `stencil_versions`,
  `contract_versions`.
- Current version constant and baseline:
  `CatalogConstants.CATALOG_MANIFEST_SCHEMA_VERSION` /
  `CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION`.
- DDL discipline (module-owned Flyway migrations):
  [`docs/migrations.md`](../migrations.md).
