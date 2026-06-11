# Plan: Catalog Wire-Format Schema Migrations (EF-style)

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
  CatalogSchemaMigration.kt        # the step interface (from / to / migrateManifest / migrateResourceDetail)
  MigrationContext.kt              # carries migrated manifest tree + source/target versions
  CatalogSchemaMigrator.kt         # @Component: collects steps, validates chain, runs it
  CatalogSchemaExceptions.kt       # TooOld / TooNew / Unknown
  steps/                           # one file per version bump, e.g. V4ToV5_<desc>.kt  (empty until first real bump)
```

Constants added to `CatalogConstants.kt`:

- keep `CATALOG_MANIFEST_SCHEMA_VERSION` (current; `4` today)
- add `CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION` (oldest upgradable; `4` initially → empty chain)

## Build sequence

### Phase 0 — Framework, with an empty chain — ✅ IMPLEMENTED

The whole framework lands before any real migration exists. With
`BASELINE == CURRENT == 4` the chain is empty.

**Discovered during implementation — shaped the gate semantics:**

- **Detail `schemaVersion` stamps have drifted.** Bundled demo/system
  _manifests_ are stamped `4`, but their committed _resource-detail_ files are
  stamped `2`/`3`, and test-fixture manifests are stamped `2` — because nothing
  ever read the field. A strict `< BASELINE → reject` would have broken demo
  loading and existing tests on day one.
- **Consequence — the manifest is the authoritative version; detail stamps are
  not trusted.** The gate reads the _manifest_ `schemaVersion` only.
- **Consequence — Phase 0 gate is lenient below current.** `> current` →
  `TooNewException` (the genuinely dangerous direction; nothing is stamped above
  4, so this is pure future-proofing and breaks nothing). `< current` with an
  **empty** chain → **pass through** and bind as-is (this is exactly how every
  such payload imports today). Strict baseline (`TooOld`) enforcement and
  detail-stamp normalisation are deferred to the first-migration PR. The
  too-old / chain-execution branches exist and are unit-tested via the
  parameterised companion gate, just not reachable with the live empty chain.

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

**What shipped:** `CatalogSchemaMigration`, `MigrationContext`,
`CatalogSchemaMigrator` (+ the parameterised companion gate &
`validateMigrationChain`), the `CatalogSchema{TooNew,TooOld,Unknown}Exception`
family (all extend `IllegalArgumentException` → existing import error paths map
them to 400), `CATALOG_MANIFEST_BASELINE_SCHEMA_VERSION`, wiring into
`CatalogClient` + `ImportCatalogZipHandler`, and the two unit tests
(`CatalogSchemaMigratorChainTest` 12 cases, `CatalogSchemaMigratorGateTest` 10
cases). Catalog import/export integration tests stay green.

1. **`CatalogSchemaMigration`** interface — `from`, `to = from + 1`, two
   identity-default methods (`migrateManifest`, `migrateResourceDetail`).
2. **`MigrationContext`** — `data class(sourceVersion, targetVersion, manifest: ObjectNode)`.
3. **`CatalogSchemaExceptions`** — `CatalogSchemaTooOldException`,
   `CatalogSchemaTooNewException`, `CatalogSchemaUnknownException`. Each
   carries the offending version and a remediation message. Map them to a
   `400`-class RFC7807 problem in the REST/UI layers (reuse the ADR-0004
   problem-details machinery — see `ApiProblem*` / `UiHandlerExceptionResolver`).
4. **`CatalogSchemaMigrator`** `@Component`:
   - constructor-injects `List<CatalogSchemaMigration>` + the `ObjectMapper`.
   - `@PostConstruct` (or init) **chain-integrity check**: sort by `from`;
     assert contiguous `BASELINE … CURRENT-1`, no gaps, no dupes; fail
     application start otherwise. Unit-tested directly.
   - `migrateManifest(raw: ByteArray): MigratedManifest` — parse tree, read
     `schemaVersion`, apply the version-range table (ADR 0006), run manifest
     steps in order, return `(migratedTree, sourceVersion)`.
   - `migrateResourceDetail(type, raw, sourceVersion): JsonNode` — parse, run
     detail steps `sourceVersion → CURRENT`, return migrated tree.
   - both no-op (identity) when `sourceVersion == CURRENT`.

   `MigratedManifest = data class(tree: JsonNode, sourceVersion: Int)`.

5. **Wire into the two chokepoints** (migrate → bind, replacing bind):
   - **`ImportCatalogZip`**: replace `objectMapper.readValue(manifestBytes,
CatalogManifest::class)` with `migrator.migrateManifest(manifestBytes)`
     then `objectMapper.treeToValue(it.tree, CatalogManifest::class)`. Thread
     `sourceVersion` into the stencil pre-scan loop and the per-resource loop,
     replacing each `readValue(detailBytes, ResourceDetail::class)` with
     `treeToValue(migrator.migrateResourceDetail(type, detailBytes, sourceVersion), ResourceDetail::class)`.
   - **`CatalogClient`**: `fetchManifest()` and `fetchResourceDetail()` are the
     remote/classpath chokepoints. Two viable shapes — pick one in review:
     - (a) inject `CatalogSchemaMigrator` into `CatalogClient` so every fetch
       returns already-migrated typed objects (uniform, but the client needs
       the source version threaded for details); or
     - (b) have `CatalogClient` return the raw tree and let `InstallFromCatalog`
       call the migrator (keeps the client dumb, matches `ImportCatalogZip`).

     **Recommendation: (b)** — one orchestration owns "migrate then bind", and
     `CatalogClient` stays a transport. `InstallFromCatalog` already fetches
     manifest-first then details lazily, so it threads `sourceVersion`
     naturally.

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

This phase is exercised the first time `epistola-model`'s catalog shape changes
non-additively (or immediately, with a synthetic bump, if we want to prove the
machinery end-to-end before a real change). Steps for any bump `N → N+1`:

1. In `epistola-model`: make the shape change; the typed model now describes
   `N+1`.
2. In the suite: bump `CATALOG_MANIFEST_SCHEMA_VERSION` to `N+1`.
3. Add `steps/V{N}ToV{N+1}_<desc>.kt` implementing `CatalogSchemaMigration`
   with `from = N`, overriding only the methods whose files changed.
4. Capture a **golden fixture**: a real exported catalog ZIP (and/or a remote
   manifest+details set) at version `N`, committed under
   `modules/epistola-core/src/test/resources/test-catalogs/wire-vN/`.
5. **Tests:**
   - `V{N}ToV{N+1}MigrationTest` (unit): exact JSON in → JSON out for the
     manifest and each affected resource type, including edge cases (absent
     optional field, empty arrays, the polymorphic-tag cases).
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
   - REST `POST /api/tenants/{id}/catalogs/import`: return the RFC7807 problem
     for too-old/too-new/unknown (Phase 0 already maps these).
   - MCP: read-only, no import tools today — no change, but note it in the PR.
2. **Docs:** update [`exchange.md`](../exchange.md) (replace the "pre-`0.6.0`
   ZIPs fail, re-export" paragraph with the migration behaviour + version-range
   table) and cross-link from [`catalog-versioning.md`](../catalog-versioning.md)
   (clarify fingerprint is preserved, not recomputed, through migration).
3. **CHANGELOG.md** under `[Unreleased]`.

## Key design decisions (carried from ADR 0006)

| Decision                         | Choice                                                                                   |
| -------------------------------- | ---------------------------------------------------------------------------------------- |
| Migrate typed or JSON?           | **JSON `JsonNode`**, before binding. The typed model is always current.                  |
| One version axis or two?         | **One** — the manifest `schemaVersion` governs the whole catalog; details share it.      |
| Migration unit                   | Per-document (manifest / each detail), with manifest migrated first and passed in `ctx`. |
| Streaming remote path            | Manifest first → `sourceVersion` threaded → each detail migrated on fetch.               |
| `release.fingerprint` on migrate | **Preserved verbatim** (source identity; excludes `schemaVersion`). Never recomputed.    |
| Newer-than-current payload       | **Reject** (`TooNew`).                                                                   |
| Older-than-baseline payload      | **Reject** (`TooOld`); baseline is a deliberate, documented floor.                       |
| Direction                        | **Up-migration only.** Down/export-to-older is a future ADR.                             |
| Chain integrity                  | Validated **at startup**; gaps/dupes fail app start (Flyway-like).                       |

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
- [ ] Too-old / too-new / unknown payloads rejected with RFC7807 problems on
      REST and inline errors in the UI.
- [ ] (When the first bump lands) a real migration step + golden fixture +
      "migrated == native" + cross-version idempotency tests, all green.
- [ ] `exchange.md`, `catalog-versioning.md`, `CHANGELOG.md` updated.
- [ ] `./gradlew unitTest integrationTest` and `ktlintCheck` green;
      `pnpm format:check` green (docs are formatted).

```

```
