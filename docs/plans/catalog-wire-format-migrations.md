# Plan: Catalog Wire-Format Schema Migrations (EF-style)

> **Whole-catalog axis (implemented).** [ADR 0007](../adr/0007-catalog-wire-format-migrations.md)
> decides a **single, catalog-wide** wire version — the manifest is authoritative
> and every resource detail echoes the same number, so the whole bundle moves
> together under one migration chain. Phase 0 reflects this: `CATALOG_SCHEMA_VERSION`
> and `CATALOG_BASELINE_SCHEMA_VERSION` hold the one window, `CatalogContentBuilder`
> stamps the manifest and every detail uniformly, and `CatalogSchemaMigrator` gates
> the payload's `schemaVersion` once and runs one chain.

Companion to [ADR 0007](../adr/0007-catalog-wire-format-migrations.md). This is
the implementation roadmap for an ordered, EF-Core-style migration chain that
upgrades an imported catalog payload from the wire `schemaVersion` it was
exported at to the version the current instance understands — applied on the
**JSON tree before typed binding**, at both the ZIP-import and
remote-subscribe chokepoints.

## Goal

> When a catalog is imported whose export was produced at an older wire
> `schemaVersion`, mechanically migrate it forward — step by step — to the
> current shape before binding and installing. The producer need not be
> reachable or re-run.

## Why JSON-tree, not typed

The protocol types (`CatalogManifest`, `ResourceDetail`, `CatalogResource` and
its seven subtypes) come from the external `epistola-model:0.6.0` jar and only
describe the **current** shape. An old-shaped payload cannot deserialize into
them. So migrations operate on `JsonNode` and the migrated, current-shape tree
is what gets bound. See ADR 0007 §"Considered options" for why this beats
versioned DTOs (Option B) and a tolerant reader (Option D).

## Package layout

All new code under `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/migrations/`:

```
migrations/
  CatalogSchemaMigration.kt        # the step interface (from / to / migrateManifest / migrateResourceDetail)
  MigrationContext.kt              # carries source/target versions of the catalog chain
  CatalogSchemaMigrator.kt         # @Component: collects steps, validates chain, runs it
  CatalogSchemaExceptions.kt       # TooOld / TooNew / Unknown
  steps/                           # one file per version bump, e.g. V4ToV5_<desc>.kt  (empty until first real bump)
```

Constants in `CatalogConstants.kt` (one catalog-wide version — ADR 0007):

- `CATALOG_SCHEMA_VERSION: Int = 4` — the current catalog wire version (the
  manifest is authoritative; every detail echoes it).
- `CATALOG_BASELINE_SCHEMA_VERSION: Int = 3` — the oldest upgradable version.
  The chain spans `[3, 4]` with the example `CatalogV3ToV4ExampleMigration`.

## Build sequence

### Phase 0 — Whole-catalog framework — ✅ IMPLEMENTED

The whole framework landed first; an empty chain was valid while `baseline ==
current`. The example `v3 → v4` step (Phase 1 below) then dropped `baseline` to
`3`, so the chain now spans `[3, 4]`.

**Whole-catalog model (ADR 0007):**

- **One catalog-wide version.** The manifest carries the authoritative
  `schemaVersion`, and `CatalogContentBuilder` stamps the **same** number on
  every resource detail, so each file is self-describing but no detail carries an
  independent per-resource version. Export stamps everything uniformly with
  `CATALOG_SCHEMA_VERSION`.
- **The gate runs once.** It reads the payload's `schemaVersion` and compares
  against `[CATALOG_BASELINE_SCHEMA_VERSION, CATALOG_SCHEMA_VERSION]`. `> current`
  → `TooNewException`; `< current` with an **empty** chain → **pass through** and
  bind as-is (how every payload imports today); `< baseline` (only reachable once
  a chain exists) → `TooOldException`. The too-old / chain-execution branches
  exist and are unit-tested via the parameterised companion gate, just not
  reachable with the live empty chain.
- **Detail-path import wiring — now wired.** `migrateAndBindResourceDetail` is
  invoked at both chokepoints (`ImportCatalogZip`'s stencil pre-scan + per-resource
  reads and `CatalogClient.fetchResourceDetail`), gating each detail by the same
  catalog `schemaVersion`. (Originally deferred from Phase 0; landed with the
  framework, so the first resource migration only needs to add its step.)

**Placement decision (supersedes the open (a)/(b) question below).** The remote
chokepoint `CatalogClient.fetchManifest` has ~10 callers, including read-only
query paths (`BrowseCatalog`, `PreviewInstall`, `CheckCatalogUpgrade`,
fingerprinting) that must _also_ see migrated content. Migrating only inside
`InstallFromCatalog` (literal option b) would leave those paths binding
un-migrated payloads. So the migrator is invoked **inside `CatalogClient`**
(fetch raw bytes via the existing `readLocalBinary`/`fetchHttpBinary` helpers →
`migrateAndBindManifest`), covering every remote consumer uniformly, and
**directly in `ImportCatalogZipHandler`** for the ZIP path. This keeps the
"migrate → bind" responsibility in one component (the migrator) while placing
its invocation at the two real byte→typed chokepoints.

**What shipped:** `CATALOG_SCHEMA_VERSION` + `CATALOG_BASELINE_SCHEMA_VERSION`
(the one catalog-wide window), uniform export stamping in `CatalogContentBuilder`
(manifest + every detail), `CatalogSchemaMigration` (whole-catalog),
`MigrationContext`, `CatalogSchemaMigrator` (one chain/gate + the parameterised
companion gate & `validateMigrationChain`), the
`CatalogSchema{TooNew,TooOld,Unknown}Exception` family (all extend
`IllegalArgumentException` → existing import error paths map them to 400),
manifest-path wiring into `CatalogClient` + `ImportCatalogZipHandler`, and the
two unit tests (`CatalogSchemaMigratorChainTest`, `CatalogSchemaMigratorGateTest`).
Catalog import/export integration tests stay green.

1. **`CatalogSchemaMigration`** interface — `from`, `to = from + 1`, and two
   methods `migrateManifest(node, ctx): ObjectNode` and
   `migrateResourceDetail(type, node, ctx): ObjectNode` (both default to
   identity). One step reshapes a whole catalog from `from` to `to` — it may
   touch the manifest tree and/or any resource-detail tree.
2. **`MigrationContext`** — `data class(sourceVersion, targetVersion)`; the
   endpoints of the catalog chain (for logging / version-conditional logic).
3. **`CatalogSchemaExceptions`** — `CatalogSchemaTooOldException`,
   `CatalogSchemaTooNewException`, `CatalogSchemaUnknownException`. Each carries
   the offending version and a remediation message; they extend
   `IllegalArgumentException` so the import error paths map them to 400. (A
   dedicated RFC 9457 problem type is Phase 2.)
4. **`CatalogSchemaMigrator`** `@Component`:
   - constructor-injects `List<CatalogSchemaMigration>` + the `ObjectMapper`;
     orders the steps into one chain.
   - init **chain-integrity check**: the steps are validated contiguous
     `baseline … current-1` against `[CATALOG_BASELINE_SCHEMA_VERSION,
CATALOG_SCHEMA_VERSION]`; a malformed chain fails application start.
     Unit-tested directly.
   - `migrateAndBindManifest(raw): CatalogManifest` — gate the catalog version,
     run each step's `migrateManifest`, then bind.
   - `migrateAndBindResourceDetail(type, raw): ResourceDetail` — gate the same
     catalog version (read off the detail's `schemaVersion`), run each step's
     `migrateResourceDetail(type, …)`, then bind. _Invoked at both chokepoints._
   - companion gate primitive — the pure gate+chain runner (no-op when
     `source == current`).
5. **Wire into the chokepoints** (migrate → bind, replacing bind):
   - **Manifest (done):** `ImportCatalogZipHandler` and `CatalogClient.fetchManifest`
     both route the manifest bytes through `migrateAndBindManifest`. The remote
     chokepoint lives in `CatalogClient` so the ~10 read-only callers
     (`BrowseCatalog`, `PreviewInstall`, `CheckCatalogUpgrade`, fingerprinting)
     see migrated content too.
   - **Detail (done):** the detail reads in `ImportCatalogZip` (stencil pre-scan +
     per-resource loop) and `CatalogClient.fetchResourceDetail` route through
     `migrateAndBindResourceDetail`, each gated by the catalog `schemaVersion`.
     A new resource-shape migration only needs to add its step — the wiring is in
     place.

6. **Tests (Phase 0):**
   - `CatalogSchemaMigratorChainTest` (unit): empty chain valid; injected
     gap/dupe/non-terminating chain fails the integrity check.
   - `CatalogSchemaVersionGateTest` (unit): manifest tree at `CURRENT` →
     fast-path; `> CURRENT` → TooNew; `< BASELINE` → TooOld; missing/garbage →
     Unknown. Drive `migrateManifest` with hand-built `JsonNode`s.
   - Integration: an existing current-version ZIP still imports byte-for-byte
     as before (regression guard that wiring is transparent). Reuse an existing
     `ImportCatalogZip` integration test catalog.

### Phase 1 — First real migration (driven by the first non-additive bump)

This phase is exercised the first time the catalog wire shape changes
non-additively (or immediately, with a synthetic bump, to prove the machinery
end-to-end). Steps for bumping the catalog version `N → N+1`:

1. In `epistola-model`: make the shape change (manifest and/or some resource
   type); the typed model now describes the catalog at `N+1`.
2. In the suite: bump `CATALOG_SCHEMA_VERSION` to `N+1` in `CatalogConstants.kt`
   (`CATALOG_BASELINE_SCHEMA_VERSION` stays at the oldest still-upgradable
   version).
3. Add `steps/V{N}ToV{N+1}_<desc>.kt` implementing `CatalogSchemaMigration` with
   `from = N` and the relevant `migrateManifest` / `migrateResourceDetail`
   overrides (only the trees that actually changed need a non-identity override).
4. **Both paths: already wired** (one-time, done) — `ImportCatalogZip`'s detail
   reads and `CatalogClient.fetchResourceDetail` route through
   `migrateAndBindResourceDetail`, and the manifest path through
   `migrateAndBindManifest`, so a migration needs no extra wiring.
5. Capture a **golden fixture**: a real exported catalog at version `N`, committed
   under `modules/epistola-core/src/test/resources/test-catalogs/wire-vN/`.
6. **Tests:**
   - `V{N}ToV{N+1}MigrationTest` (unit): exact JSON in → JSON out for each changed
     tree, including edge cases (absent optional field, empty arrays, polymorphic-tag).
   - `WireVersionImportFixtureTest` (integration): import the vN golden
     fixture into a real DB via both `ImportCatalogZip` and the remote path;
     assert the installed resources equal those from importing an equivalent
     **current-version** export of the same catalog. This is the "migrated old
     == native new" guarantee.
   - **Cross-version idempotency:** import vN fixture, then re-import; assert
     the second import is all `SKIPPED` (no churn). This proves migration is
     faithful enough that the stencil JSONB byte-identity check (ADR 0003)
     still recognises the content as unchanged.

### Phase 2 — Operator-facing polish

1. **Surfaces (CLAUDE.md item 11 — web UI / REST / MCP):**
   - UI import dialog: when a `CatalogSchema*Exception` is thrown, render the
     remediation message inline (same `alert-error` slot ADR 0003 uses for
     stencil conflicts). On a _successful_ migrated import, show a subtle
     "upgraded from wire v{N} to v{CURRENT}" note.
   - REST `POST /api/tenants/{id}/catalogs/import`: return the RFC 9457 problem
     for too-old/too-new/unknown (Phase 0 already maps these).
   - MCP: read-only, no import tools today — no change, but note it in the PR.
2. **Docs:** update [`exchange/README.md`](../exchange/README.md) (replace the "pre-`0.6.0`
   ZIPs fail, re-export" paragraph with the migration behaviour + version-range
   table) and cross-link from [`catalog-versioning.md`](../catalog-versioning.md)
   (clarify fingerprint is preserved, not recomputed, through migration).
3. **CHANGELOG.md** under `[Unreleased]`.

## Key design decisions (carried from ADR 0007)

| Decision                         | Choice                                                                                                                |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Migrate typed or JSON?           | **JSON `JsonNode`**, before binding. The typed model is always current.                                               |
| One version axis or per-part?    | **One catalog-wide version** — the manifest is authoritative, every detail echoes it, one chain for the whole bundle. |
| Migration unit                   | One step per `from`; a step may reshape the manifest tree and/or any resource-detail tree.                            |
| Streaming remote path            | Manifest first → each detail migrated on fetch, gated by the same catalog `schemaVersion`.                            |
| `release.fingerprint` on migrate | **Preserved verbatim** (source identity; excludes `schemaVersion`). Never recomputed.                                 |
| Newer-than-current payload       | **Reject** (`TooNew`).                                                                                                |
| Older-than-baseline payload      | **Reject** (`TooOld`); baseline is a deliberate, documented floor.                                                    |
| Direction                        | **Up-migration only.** Down/export-to-older is a future ADR.                                                          |
| Chain integrity                  | Validated **at startup**; gaps/dupes fail app start (Flyway-like).                                                    |

## Risks & mitigations

- **A migration produces an un-bindable tree.** → Mandatory golden-fixture
  integration test per kept version; the round-trip test binds and installs,
  so a bad transform fails CI.
- **Forgetting to ship a migration with a bump.** → Startup chain-integrity
  check (non-contiguous chain won't boot) + the fixture test for the new
  version won't exist/pass.
- **Migration silently diverges from a native current export.** → The
  "migrated vN == native current" assertion in `WireVersionImportFixtureTest`
  is the explicit guard.
- **Fingerprint/idempotency drift.** → Cross-version re-import idempotency test
  (all `SKIPPED`) catches any shape infidelity that would defeat the JSONB
  byte-identity check.
- **Two divergent chokepoints (ZIP vs remote).** → Both routed through the one
  `CatalogSchemaMigrator`; the fixture test runs _both_ paths.

## Definition of done

- [ ] `CatalogSchemaMigrator` + interfaces + exceptions merged; chain-integrity
      check fails app start on a broken chain (tested).
- [ ] Both chokepoints (`ImportCatalogZip`, `InstallFromCatalog`/`CatalogClient`)
      route through the migrator; current-version imports unchanged (regression
      test green).
- [ ] Too-old / too-new / unknown payloads rejected with RFC 9457 problems on
      REST and inline errors in the UI.
- [ ] (When the first bump lands) a real migration step + golden fixture +
      "migrated == native" + cross-version idempotency tests, all green.
- [ ] `exchange/README.md`, `catalog-versioning.md`, `CHANGELOG.md` updated.
- [ ] `./gradlew unitTest integrationTest` and `ktlintCheck` green;
      `pnpm format:check` green (docs are formatted).
