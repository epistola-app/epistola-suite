# Catalog Versioning & Upgrading

How catalogs declare a version, how "is this a new version?" is decided, and
how that flows across the web UI, REST, MCP, catalog exchange and the bundled
catalogs. Companion to [`docs/exchange/README.md`](exchange/README.md).

> This covers the **release version + content fingerprint** axis. For how it
> relates to the other version concepts (template/contract/stencil publish, and
> the wire-format `schemaVersion`), see [version-axes.md](version-axes.md).

## Why

Authored catalogs had no real version: the ZIP export stamped
`LocalDate.now()`, and every "changed?" decision (`DemoLoader`,
`SystemCatalogBootstrap`, `UpgradeCatalog`) was an exact **string equality** on
`installed_release_version` (a SUBSCRIBED-only column). An author could not say
"this is now a new version" with any meaning, and re-export produced a
different "version" even when nothing changed (or the same one when it did).

## Model

A catalog is **one row per `(tenant_key, slug)` with one live resource set** —
there is never a parallel install of two versions.

- **AUTHORED (publisher side)** — one live, editable working copy. Cutting a
  release records an **immutable boundary** in `catalog_releases`
  (author-set SemVer + content fingerprint + notes + a manifest snapshot) and
  advances the `catalogs.released_version` / `released_fingerprint` /
  `released_at` pointer. `catalog_releases` is AUTHORED release **history**
  (changelog), not parallel installs and **not** the consumer upgrade-diff
  baseline (a SUBSCRIBED consumer's DB has no `catalog_releases` rows).
- **SUBSCRIBED (consumer side)** — exactly one installed state.
  `installed_release_version` + `installed_fingerprint` record which release is
  installed; `installed_resource_fingerprints` (JSONB) records the per-resource
  source-side digests of that release — the Phase 2 upgrade-diff baseline.
  `UpgradeCatalog` replaces resources **in place**.

`installed_*` (SUBSCRIBED) and `released_*` / `catalog_releases` (AUTHORED) are
orthogonal — `CatalogType` is exclusive, so only one applies to a given
catalog. An authored `1.4.0` exported and subscribed elsewhere flows its
version + fingerprint through the manifest into the subscriber's
`installed_release_version` / `installed_fingerprint`, so values stay coherent
end to end.

## Versioning scheme: SemVer + content fingerprint

- **SemVer** (`MAJOR.MINOR.PATCH`) is author-controlled, recorded per release.
  Implemented by the dependency-free
  [`SemVer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/SemVer.kt)
  value type (parse / compare / bump). Releases must strictly increase.
- **Content fingerprint** is a deterministic lowercase-hex SHA-256 of the
  catalog's canonical content, computed by
  [`CatalogCanonicalizer`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogCanonicalizer.kt)
  (wired by `CatalogFingerprintService`). It identifies _content_ independently
  of the version label — "is this actually new?" is a fingerprint comparison,
  not a string compare.

### Fingerprint algorithm

Hashes, in order: an identity line (`slug name description`); each resource
sorted by `"$type/$slug"`, rendered as `key  <canonical resource JSON>  <asset
bytes SHA-256 | "" | "MISSING">`; then a sorted, `;`-joined dependencies line.
Resource JSON is canonical: the serialized `ResourceDetail` is parsed (floats
as `BigDecimal`), the `resource` subtree taken, every object's keys recursively
sorted (array order preserved — arrays are ordered content), then compact-written.
Excluded by construction: `release.*`, `schemaVersion`, every `updatedAt`,
`detailUrl`. Asset _binary bytes_ are folded in via their own SHA-256 (mirrors
the font-family fingerprint), so swapping an image flips the fingerprint even
though the `AssetResource` JSON is unchanged.

**Wire-format migration is fingerprint-transparent.** Because `schemaVersion` is
excluded, the whole-catalog import migration ([ADR 0007](adr/0007-catalog-wire-format-migrations.md),
[`docs/exchange/`](exchange/README.md#wire-format-version-gate)) — which upgrades
an older payload's shape and re-stamps `schemaVersion` to the current catalog
version — is **preserved verbatim**: `release.fingerprint` / `release.version` are never
touched or recomputed during migration, so a migrated import compares as the same
source release and the idempotent-re-import SKIP still holds.

**One definition (invariant).** There is a single canonicalization, run over
the **serialized resource-detail JSON** (the exact wire form). The value
stamped into an exported manifest / stored on release and the value any
consumer recomputes from those detail bytes via
[`fingerprintFromSource`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogCanonicalizer.kt)
**agree by construction** — there is no separate "typed object" path that could
diverge for `Float`/`Double` fields (numbers never round-trip through
`Double`/`Float.toString`, whose algorithm changed across JDKs — JDK-4511638).
Guarded by `CatalogFingerprintEquivalenceTest`. The export ZIP and the
fingerprint are built from the **same**
[`CatalogContentBuilder`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/CatalogContentBuilder.kt),
so "the bytes you export" ≡ "the bytes you fingerprint".

## Cutting a release (AUTHORED)

The web UI catalog list shows a **Release new version** action (AUTHORED only)
opening a dialog: Patch / Minor / Major quick-picks, an editable SemVer field,
release notes, and a drift line ("This working copy has unreleased changes" vs
"No changes since the last release"). It dispatches
[`ReleaseCatalogVersion`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/ReleaseCatalogVersion.kt):
AUTHORED-only, parses + monotonic-checks the SemVer, computes the fingerprint,
inserts the `catalog_releases` row (with the manifest snapshot) and advances
the catalog pointer — one transaction. Releasing content byte-identical to a
prior release is allowed (notes-only re-release) but logged.

[`GetCatalogReleaseStatus`](../modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/GetCatalogReleaseStatus.kt)
is the shared primitive (latest version/fingerprint, working fingerprint,
`hasUnreleasedChanges`, suggested next bumps, history).

## Export drift policy

`ExportCatalogZip` always emits a fingerprint describing the **actual exported
bytes**. The version label encodes release state and export is never blocked:

- never released → `0.0.0-dev` (logged WARN)
- working copy differs from the latest release → `<version>-dev` (logged WARN)
- working copy matches the latest release → the clean released version

`-dev` makes drift unmistakable in the filename and manifest. (Serving an
immutable release snapshot so subscribers never see in-progress edits is a
Phase 2 hardening.)

### Drift hint in the catalog list

So the `-dev` outcome isn't only discovered at download time, the catalog
list shows `v0.1.0 · pending changes` for an AUTHORED, released catalog whose
working copy drifted. This is a **cheap heuristic, not the fingerprint** (the
export `-dev`/fingerprint stays authoritative): the catalog-management list
query `ListCatalogsForManagement` returns each catalog as a `CatalogListRow`
(the shared `Catalog` plus a list-only `pendingChanges` flag), the flag
computed in one `LEFT JOIN` comparing `MAX(updated_at | published_at |
created_at)` across the catalog's resource/version tables to the baseline
`GREATEST(released_at, imported_at)`. The flag is **not** on `Catalog` itself
(browse/REST/MCP read that shared model and don't show drift); it is purely a
list-page concern, so the list-page query owns it and the template reads
`row.pendingChanges` — no parallel id set, no template-side cross-reference.

All three timestamps share the **database** clock: resource `updated_at` and
`catalogs.imported_at` are Postgres `NOW()`, and `ReleaseCatalogVersion`
stamps `released_at` via `SELECT NOW()` (not JVM time) precisely so this
comparison is exact and not skewed by JVM↔DB clock drift.

`catalogs.imported_at` is set at the **end** of `ImportCatalogZip` (after the
resource upserts, so ≥ their `updated_at`). It exists specifically so a no-op
ZIP **re-import** — which bumps every resource's `updated_at` — advances the
baseline in lockstep and is **not** mistaken for an edit. Column choice
mirrors what the fingerprint sees: `updated_at` for live rows, `published_at`
for `*_versions` (a draft-only edit doesn't change the published export, so it
correctly stays clean), `created_at` for `assets`.

Conservative by construction: it can **over-warn** (edit-then-revert to
byte-identical, a re-publish of unchanged content) and the only **under-warn**
is a deletion-only change (no surviving row's timestamp moves) — in every case
the export's `-dev` label/fingerprint is the authoritative backstop.

## Importing a ZIP

A ZIP import always targets a catalog **type**. A slug that already exists with
a _different_ type is rejected (it would flip ownership semantics). What the
import then means depends entirely on that type:

### AUTHORED — content transport into your editable copy

A release is an **authorship act**, so a ZIP import only adopts a release in
the one case where there is no authorship to protect:

- **Import that _creates_ the catalog** → no release history yet, so the
  manifest's version becomes the **initial release**, provided it is a clean
  SemVer (not a `-dev`/legacy label), a `release.fingerprint` is present, and
  every resource imported. The ZIP round-trip fingerprint is deterministic, so
  the recorded release row is real and consistent — not fabricated. Result: the
  catalog shows the published version, not "unreleased".
- **Import into an _existing_ AUTHORED catalog** → an _edit_ to a catalog you
  already version. Release state is **never** fabricated or changed; the
  working copy shows as drift ("unreleased changes") and the owner releases
  deliberately. The import dialog's **"If this catalog already exists"** choice
  (`ImportCatalogZip.authoredMode`) decides what happens to resources you have
  locally that the ZIP doesn't:
  - **`MERGE`** (default) — upsert the ZIP's resources, **keep** local-only
    resources (overlay);
  - **`REPLACE`** — make the catalog _exactly_ the ZIP: also **delete**
    local-only resources. Conflict-checked **before mutating** — a pruned
    resource still referenced from another catalog blocks the whole import
    (`CatalogUpgradeConflictException`), the same shared
    `CatalogUpgradeAnalyzer` the SUBSCRIBED path uses. A failed install skips
    the prune (no half-replace). Release state is _still_ untouched.

  (Either way, resources present in the ZIP overwrite the local copy — that is
  inherent to importing them; the choice is only about local-_only_ resources.)

  The REST `importCatalog` operation exposes the same choice as an optional
  `authoredMode` form field (`MERGE` default, `REPLACE`), and its
  `ImportCatalogResponse.aborted` boolean reports whether a SUBSCRIBED-ZIP
  upgrade aborted on a failed install (catalog left on its previous release —
  a retry-safe outcome the `installed`/`updated`/`failed`/`total` counts alone
  cannot express). There is no MCP import tool yet, so import is not an MCP
  surface.

- **`-dev`/never-released manifest** → nothing real to adopt; stays unreleased.

### SUBSCRIBED — the ZIP _is_ the upgrade

A SUBSCRIBED catalog is a managed mirror. With no source URL to poll, importing
a newer ZIP **is** its upgrade — and it runs the **full `UpgradeCatalog`
contract**, the ZIP merely being the transport instead of an HTTP source:

- **conflict-checked before any mutation** — a stale resource still referenced
  from another catalog blocks the whole import (`CatalogUpgradeConflictException`);
- resources are installed/replaced, then **stale resources are pruned**
  (shared `CatalogUpgradeAnalyzer.removeStale` — one definition with the URL
  path);
- **abort-on-failed-install** — if any resource fails, stale is _not_ pruned
  and `installed_*` is _not_ advanced, so the catalog stays on its previous
  release and a re-import retries (never a silent half-upgrade);
- on success `installed_release_version` / `installed_fingerprint` /
  `installed_resource_fingerprints` are advanced from the manifest, computed
  with the **exact same** canonicalization as a URL source — so drift
  detection and the upgrade preview are consistent regardless of transport.

So SUBSCRIBED upgrade has two equivalent transports — re-fetch a source URL
(`UpgradeCatalog`) or re-import a ZIP — with identical semantics.

## Bundled catalogs

`demo` and `system` ship a real SemVer and a committed `release.fingerprint` in
their `catalog.json`.
[`BundledCatalogFingerprintTest`](../modules/epistola-core/src/test/kotlin/app/epistola/suite/catalog/BundledCatalogFingerprintTest.kt)
recomputes the fingerprint from classpath content and asserts it equals the
committed value — the drift gate (analogous to the `pg_dump --schema-only`
migration check). Editing a bundled resource without regenerating the committed
fingerprint fails the build. `DemoLoader` and `InstallSystemCatalog` decide
install/upgrade/no-op by **fingerprint** (`manifest.release.fingerprint` vs
`catalogs.installed_fingerprint`), logging the SemVer transition for humans.

To regenerate after an intentional change: run the test, paste the reported
"actual" fingerprint into the catalog's `catalog.json`, and bump
`release.version`.

## Surfaces

| Surface  | Exposure                                                                |
| -------- | ----------------------------------------------------------------------- |
| Web UI   | Version column (`v<version>` / `unreleased`); Release dialog (AUTHORED) |
| REST     | `CatalogDto.releasedVersion` + `fingerprint` (read-only)                |
| MCP      | `CatalogInfo.releasedVersion` + `fingerprint` (read-only)               |
| Exchange | `ReleaseInfo.fingerprint` in the manifest; `schemaVersion` 4            |

The release **action** is intentionally UI-only in Phase 1 (deliberate, human;
read-state parity is delivered everywhere). A REST/MCP release endpoint can be
added later if automation demand appears.

## Roadmap

- **Phase 2 — upgrade diff/preview.** _Backend landed._ A SUBSCRIBED consumer
  has **no `catalog_releases` snapshot** (that table is AUTHORED/publisher
  state), so the diff is **source-vs-source**, not snapshot-vs-installed: the
  per-resource source-side digests are captured into
  `catalogs.installed_resource_fingerprints` (JSONB) at `RegisterCatalog` /
  `UpgradeCatalog`, exactly alongside `installed_fingerprint` and with the same
  provenance (computed from the source manifest, never publisher-authored).
  `PreviewCatalogUpgrade` re-fetches the manifest, recomputes the same
  per-resource digests, and classifies every `(type, slug)` as
  ADDED / REMOVED / CHANGED / UNCHANGED — so a CHANGED verdict means the
  _publisher_ changed that resource, never install round-trip noise. The
  per-resource digest reuses the **exact** whole-catalog fingerprint pipeline
  (`CatalogCanonicalizer.perResourceFingerprints*`); cross-catalog conflicts on
  the removal set are computed by the shared `CatalogUpgradeAnalyzer` that
  `UpgradeCatalog` also throws on, so a blocking conflict surfaces in the
  preview before Apply. **UI:** an **explicit, user-driven** check — a refresh
  **icon** in the row's actions cell (never auto-polls) renders the result in
  the **Version column**: green `vX` (up to date), greyed `vX` + clickable
  `→ vY` (update available — the new version named; opens the Review dialog),
  `vX · ZIP` (ZIP-managed mirror — no source URL, upgrade by re-importing),
  or `vX · check failed` (source unreachable). The "Review catalog upgrade"
  dialog shows the version delta, change buckets, conflicts with a
  server-rendered disabled Apply, and an opt-in "also install new resources"
  wired to `UpgradeCatalog.includeNewSlugs`. **Read parity:** REST
  `GET /tenants/{tenantId}/catalogs/{catalogId}/upgrade-preview`
  (`CatalogUpgradeDiff`) and MCP `preview_catalog_upgrade`; the upgrade
  _action_ stays UI-only. Still open: ZIP-import dry-run.
- **Phase 3 — dependency SemVer ranges.** Implement the reserved
  `DependencyRef.versionRange` (catalog-level, e.g. `system >=1.2.0 <2.0.0`),
  validated against the dependency catalog's installed/released version at
  import/upgrade; surfaced in the Phase 2 preview. The snapshot JSON already
  round-trips an unknown `versionRange` and `SemVer` is the comparison
  primitive — no Phase 1 rework.
