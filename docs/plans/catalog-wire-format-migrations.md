# Plan: Catalog Wire-Format Schema Migrations (EF-style)

> **Per-part axis (implemented).** [ADR 0006](../adr/0006-catalog-wire-format-migrations.md)
> decides a **per-part** version axis — the manifest and each resource type
> version independently, one chain each. Phase 0 now reflects this: a `CatalogPart`
> enum and `CATALOG_PART_SCHEMAS` map hold the per-part windows, export stamps each
> detail with its part's version, and `CatalogSchemaMigrator` gates/migrates per
> part. A few passages below still use single-axis phrasing from the original
> draft — read "the wire `schemaVersion`" as "each part's `schemaVersion`" and
> "the chain" as "that part's chain".

Companion to [ADR 0006](../adr/0006-catalog-wire-format-migrations.md). This is
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
is what gets bound. See ADR 0006 §"Considered options" for why this beats
versioned DTOs (Option B) and a tolerant reader (Option D).

## Package layout

All new code under `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/migrations/`:

```
migrations/
  CatalogSchemaMigration.kt        # the step interface (part / from / to / migrate)
  MigrationContext.kt              # carries source/target versions of the part's chain
  CatalogSchemaMigrator.kt         # @Component: collects steps, validates chain, runs it
  CatalogSchemaExceptions.kt       # TooOld / TooNew / Unknown
  steps/                           # one file per version bump, e.g. V4ToV5_<desc>.kt  (empty until first real bump)
```

Constants in `CatalogConstants.kt` (per-part — ADR 0006):

- `CatalogPart` enum — the manifest + each resource type, with its wire `type`.
- `CATALOG_PART_SCHEMAS: Map<CatalogPart, PartSchemaWindow(baseline, current)>` —
  the current + oldest-upgradable version of **each part** (manifest `4`; asset/
  theme/stencil/template `2`; attribute/code-list `3`; font `1`). `baseline ==
current` for every part today → all chains empty.
- `CATALOG_MANIFEST_SCHEMA_VERSION` / `…_BASELINE` derive from the map (the
  manifest part's window) for the existing manifest call sites.

## Build sequence

### Phase 0 — Per-part framework, with empty chains — ✅ IMPLEMENTED

The whole framework lands before any real migration exists. Every part's
`baseline == current`, so all chains are empty.

**Per-part model (ADR 0006):**

- **Each part owns its version.** The committed bundled details carry independent
  per-type numbers (template/theme/stencil/asset `2`; attribute/code-list `3`;
  manifest `4`) — these are **canonical**, recorded in `CATALOG_PART_SCHEMAS`.
  Export stamps each detail with **its part's** current version, so a re-export
  preserves them instead of flattening to the manifest version.
- **The gate is per part.** It reads the part's own tree `schemaVersion` and
  compares against that part's window. `> current` → `TooNewException`; `<
current` with an **empty** chain → **pass through** and bind as-is (how every
  payload imports today, including the under-stamped test-fixture manifests at
  `2`); `< baseline` (only reachable once a part has a chain) → `TooOldException`.
  The too-old / chain-execution branches exist and are unit-tested via the
  parameterised companion gate, just not reachable with the live empty chains.
- **Detail-path import wiring — now wired.** `migrateAndBindResourceDetail` is
  invoked at both chokepoints (`ImportCatalogZip`'s stencil pre-scan + per-resource
  reads and `CatalogClient.fetchResourceDetail`), gating each detail by its own
  `schemaVersion`. (Originally deferred from Phase 0; landed with the per-part
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

**What shipped:** `CatalogPart` + `CATALOG_PART_SCHEMAS` (per-part windows),
per-part export stamping in `CatalogContentBuilder`, `CatalogSchemaMigration`
(per-part), `MigrationContext`, `CatalogSchemaMigrator` (per-part chains/gate +
the parameterised companion gate & `validateMigrationChain`), the
`CatalogSchema{TooNew,TooOld,Unknown}Exception` family (all extend
`IllegalArgumentException` → existing import error paths map them to 400),
manifest-path wiring into `CatalogClient` + `ImportCatalogZipHandler`, and the
two unit tests (`CatalogSchemaMigratorChainTest`, `CatalogSchemaMigratorGateTest`).
Catalog import/export integration tests stay green.

1. **`CatalogSchemaMigration`** interface — `part: CatalogPart`, `from`,
   `to = from + 1`, and a single `migrate(node, ctx): ObjectNode` (the step
   belongs to one part and migrates that part's tree).
2. **`MigrationContext`** — `data class(sourceVersion, targetVersion)`; the
   endpoints of the part's chain (for logging / version-conditional logic). No
   cross-part data is threaded yet — add it here if a step ever needs it.
3. **`CatalogSchemaExceptions`** — `CatalogSchemaTooOldException`,
   `CatalogSchemaTooNewException`, `CatalogSchemaUnknownException`. Each carries
   the offending version and a remediation message; they extend
   `IllegalArgumentException` so the import error paths map them to 400. (A
   dedicated RFC 9457 problem type is Phase 2.)
4. **`CatalogSchemaMigrator`** `@Component`:
   - constructor-injects `List<CatalogSchemaMigration>` + the `ObjectMapper`;
     **groups steps by `part`** into `chainsByPart`.
   - init **chain-integrity check per part**: each part's steps validated
     contiguous `baseline … current-1` against its `CATALOG_PART_SCHEMAS` window;
     a malformed chain (any part) fails application start. Unit-tested directly.
   - `migrateAndBindManifest(raw): CatalogManifest` — gate/migrate by the
     **manifest** part's window, then bind.
   - `migrateAndBindResourceDetail(type, raw): ResourceDetail` — gate/migrate by
     **that resource type's** window (keyed off the detail's own `schemaVersion`),
     then bind. _Invoked at both chokepoints._
   - companion `migratePartTree(tree, byFrom, baseline, current)` — the pure
     gate+chain primitive (no-op when `source == current`).
5. **Wire into the chokepoints** (migrate → bind, replacing bind):
   - **Manifest (done):** `ImportCatalogZipHandler` and `CatalogClient.fetchManifest`
     both route the manifest bytes through `migrateAndBindManifest`. The remote
     chokepoint lives in `CatalogClient` so the ~10 read-only callers
     (`BrowseCatalog`, `PreviewInstall`, `CheckCatalogUpgrade`, fingerprinting)
     see migrated content too.
   - **Detail (done):** the detail reads in `ImportCatalogZip` (stencil pre-scan +
     per-resource loop) and `CatalogClient.fetchResourceDetail` route through
     `migrateAndBindResourceDetail`, each gated by the detail's own `schemaVersion`.
     A new resource-type migration only needs to add its step — the wiring is in
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

This phase is exercised the first time some part's `epistola-model` shape
changes non-additively (or immediately, with a synthetic bump, to prove the
machinery end-to-end). Steps for bumping **one part** `P` from `N → N+1`:

1. In `epistola-model`: make `P`'s shape change; the typed model now describes
   `P` at `N+1`.
2. In the suite: bump **that part's** `current` in `CATALOG_PART_SCHEMAS`
   (`CatalogPart.P → PartSchemaWindow(baseline, N+1)`). Other parts are untouched.
3. Add `steps/{P}V{N}ToV{N+1}_<desc>.kt` implementing `CatalogSchemaMigration`
   with `part = CatalogPart.P`, `from = N`, and the one-version `migrate`.
4. **Detail path: already wired** (one-time, done) — `ImportCatalogZip`'s detail
   reads and `CatalogClient.fetchResourceDetail` route through
   `migrateAndBindResourceDetail`, so a resource-type migration needs no extra
   wiring. (The manifest path is likewise wired.)
5. Capture a **golden fixture**: a real exported catalog at `P = N`, committed
   under `modules/epistola-core/src/test/resources/test-catalogs/wire-{P}vN/`.
6. **Tests:**
   - `{P}V{N}ToV{N+1}MigrationTest` (unit): exact JSON in → JSON out for `P`,
     including edge cases (absent optional field, empty arrays, polymorphic-tag).
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

## Key design decisions (carried from ADR 0006)

| Decision                         | Choice                                                                                    |
| -------------------------------- | ----------------------------------------------------------------------------------------- |
| Migrate typed or JSON?           | **JSON `JsonNode`**, before binding. The typed model is always current.                   |
| One version axis or per-part?    | **Per-part** — the manifest and each resource type version independently, one chain each. |
| Migration unit                   | One step per `(part, from)`; a step declares its `part` and migrates that part's tree.    |
| Streaming remote path            | Manifest first → each detail migrated on fetch by its **own** `schemaVersion`.            |
| `release.fingerprint` on migrate | **Preserved verbatim** (source identity; excludes `schemaVersion`). Never recomputed.     |
| Newer-than-current payload       | **Reject** (`TooNew`).                                                                    |
| Older-than-baseline payload      | **Reject** (`TooOld`); baseline is a deliberate, documented floor.                        |
| Direction                        | **Up-migration only.** Down/export-to-older is a future ADR.                              |
| Chain integrity                  | Validated **at startup**; gaps/dupes fail app start (Flyway-like).                        |

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
