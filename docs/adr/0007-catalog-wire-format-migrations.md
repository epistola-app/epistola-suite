# ADR 0007: Catalog Wire-Format Schema Migrations

- **Status:** Accepted
- **Date:** 2026-06-09
- **Deciders:** Epistola team
- **Tags:** catalog, exchange, versioning, import, wire-format

## Context

The catalog exchange wire format (`catalog.json` manifest + per-resource
`resources/{type}/{slug}.json` detail files) carries an integer
`schemaVersion`, currently `4` (`CatalogConstants.CATALOG_SCHEMA_VERSION`).
Both the manifest and every resource-detail file are stamped with that same
constant at export time (`CatalogContentBuilder`).

The shape of that wire format is owned by an **external dependency**,
`app.epistola.contract:epistola-model:0.6.0`. The suite imports
`CatalogManifest`, `ResourceDetail`, and the `CatalogResource` polymorphic
hierarchy (`TemplateResource`, `ThemeResource`, `StencilResource`,
`AttributeResource`, `CodeListResource`, `FontResource`, `AssetResource`)
from that jar. Those typed classes only ever describe the **current** shape —
there is no historical typed model.

Today, `schemaVersion` is **read but never validated** on import. The two
deserialization chokepoints both bind raw bytes straight to the current typed
model:

- Manifest: `ImportCatalogZip.kt:163` and `CatalogClient.fetchManifest()`.
- Resource detail: `ImportCatalogZip.kt` (stencil pre-scan + per-resource
  loop) and `CatalogClient.fetchResourceDetail()`.

Both the **ZIP import** path (`ImportCatalogZip`) and the **remote-subscribe**
path (`InstallFromCatalog` → `CatalogClient`) funnel through the same
`ObjectMapper.readValue(bytes, …::class.java)` calls.

Because Jackson is configured to ignore unknown properties, **additive**
wire-format changes (a new optional field) already round-trip safely: an old
ZIP simply lacks the field and a new ZIP's extra field is ignored by an old
reader. But any **non-additive** change — a rename, a type change, a moved or
restructured field, a new _required_ field, a polymorphic-tag change — breaks
binding. The current answer, per ADR 0003 and CLAUDE.md, is "the project is
pre-release; re-export the catalog." `StencilResource.version` became required
in `epistola-model 0.6.0` and pre-`0.6.0` ZIPs are told to re-export with no
shim.

That "just re-export" stance stops working the moment two Epistola instances
at different versions need to exchange catalogs — which is precisely the
post-production reality the suite is heading toward (SUBSCRIBED catalogs
pulling from a remote publisher, ZIPs handed between deployments, bundled
demo/system catalogs surviving an app upgrade). When the producer is at wire
version N and the consumer is at N+2, "re-export" is not available: the
consumer cannot reach back and re-run the producer.

We want the same discipline EF Core gives a database: an **ordered chain of
migrations**, each taking the artifact from version N to N+1, applied in
sequence at the import boundary so an old export is mechanically upgraded to
the shape the current code understands before it is bound and installed.

### Decision drivers

- **Forward compatibility across version skew.** A catalog produced at any
  supported past wire version must import into the current instance without
  the producer's involvement.
- **The typed model is always current.** We cannot deserialize an
  old-shaped payload into the current `epistola-model` classes. Whatever
  migrates must run _before_ typed binding.
- **One uniform path.** ZIP import and remote subscribe must share the
  migration logic — drift between them is how silent corruption happens.
- **Streaming-friendly.** The remote-subscribe path fetches the manifest
  first and resource details lazily, one at a time. The mechanism cannot
  require the whole catalog in memory.
- **Deterministic and testable.** A migration is a pure function on JSON;
  it must be unit-testable in isolation and as a golden-fixture round-trip.
- **Fingerprint identity is preserved.** `release.fingerprint` is the
  _source's_ content identity and excludes `schemaVersion` by construction
  (`catalog-versioning.md`). Migration must not change which source release a
  payload represents.
- **Fail loud on the un-migratable.** A payload newer than this instance
  understands, or older than the oldest migration we keep, must be rejected
  with a clear, actionable message — never silently half-imported.

## Considered options

### Option A — Keep "re-export only" (status quo)

Continue rejecting any payload whose shape doesn't bind to the current model.
Document the supported wire version; tell operators to re-export from an
upgraded source.

#### A — Pros

- Zero new machinery.
- No historical shapes to keep working.

#### A — Cons

- Impossible across genuine version skew: a consumer cannot re-run a
  producer it does not control (remote SUBSCRIBED catalogs, third-party ZIPs).
- Every non-additive wire change becomes a coordinated multi-instance upgrade
  event.
- Does not match the EF-style behaviour the team wants.

### Option B — Versioned typed models (one DTO set per wire version)

Keep a frozen copy of the typed model for every past wire version
(`CatalogManifestV4`, `CatalogManifestV5`, …) and a mapper between adjacent
versions. Deserialize into the version-matched DTO, then map up.

#### B — Pros

- Type-safe at every hop.
- Migrations are ordinary Kotlin object-to-object mappers.

#### B — Cons

- The model lives in an **external** jar (`epistola-model`). Freezing a DTO
  per version means either vendoring historical copies into this repo or
  publishing a sprawling versioned hierarchy upstream — heavy multi-repo
  coordination for every bump.
- A full DTO clone per version is enormous boilerplate for changes that often
  touch one field.
- Polymorphic `CatalogResource` multiplies the clone cost across seven
  subtypes even when a migration touches one.

### Option C — JSON-tree migration chain before binding (chosen)

Introduce a `CatalogSchemaMigrator` that operates on **`JsonNode`**, not typed
objects. A registry of `CatalogSchemaMigration` steps each declares
`from`/`to = from + 1` and provides pure JSON transforms. At each
deserialization chokepoint, raw bytes are parsed to a tree, the migrator runs
every step from the payload's `schemaVersion` up to
`CATALOG_SCHEMA_VERSION`, and only the resulting current-shape tree
is bound to the `epistola-model` types and handed to the existing importers.

#### C — Pros

- Works precisely because it sidesteps the "typed model is always current"
  problem — migrations run on untyped JSON.
- A migration that touches one field is a few lines on one `JsonNode`, not a
  DTO clone.
- No change to `epistola-model`; the current jar stays the single source of
  the current shape.
- One migrator, injected at both chokepoints, gives ZIP and remote the same
  behaviour for free.
- Streaming-friendly: the manifest and each detail are independent JSON
  documents migrated on arrival.

#### C — Cons

- JSON-tree transforms are not statically type-checked — a migration can
  produce a tree that fails to bind. Mitigated by mandatory golden-fixture
  tests (every kept version has a captured payload asserted to import).
- The team must remember to ship a migration with every non-additive bump.
  Mitigated by a startup chain-integrity check (gaps/dupes fail fast) and a
  CI round-trip test.

### Option D — Tolerant reader (one mapper, many optional fields)

Make the current typed model permissive: every field optional, custom
deserializers that accept old and new shapes, `@JsonAlias` for renames.

#### D — Pros

- No separate migration step; the reader absorbs history.

#### D — Cons

- Pushes historical knowledge into the production model permanently — it never
  gets cleaner, only more permissive.
- Cannot express structural moves (field relocated between manifest and
  detail) or value transforms cleanly.
- Erodes the invariants the current model is supposed to enforce (required
  fields become optional forever).

## Decision

Adopt **Option C**: a JSON-tree migration chain applied at the import boundary,
modelled on EF Core migrations — with **one chain for the whole catalog** (a
single catalog-wide version; see the version axis below).

### The version axis — one per catalog

The wire format has a **single, catalog-wide** `schemaVersion`. The manifest
(`catalog.json`) carries the **authoritative** version; every resource detail
(`asset`, `codeList`, `font`, `attribute`, `theme`, `stencil`, `template`)
carries the **same** number so each file is self-describing, but a catalog is at
**one** version and the whole bundle moves together. There is no independent
per-resource version.

(We considered versioning each part independently — the manifest and each
resource type at its own number, one chain each — but rejected it: it makes a
catalog "a set of versions", complicates the gate and the streaming detail path,
and a re-export has to re-derive each part's number. A single catalog version is
simpler to reason about, to gate, and to migrate: one number, one chain. The
contract docs, [`docs/exchange/`](../exchange/README.md), record each resource's
current _shape_ — but all under the one catalog version.)

The catalog has one `CURRENT` and one `BASELINE` (the oldest version the chain
can still upgrade; payloads below it are rejected — we may drop very old
migrations rather than keep them forever). `CATALOG_SCHEMA_VERSION` and
`CATALOG_BASELINE_SCHEMA_VERSION` (both `4` today) hold them. Export stamps the
manifest **and every detail** with `CATALOG_SCHEMA_VERSION`.

### The migration unit

```kotlin
interface CatalogSchemaMigration {
    val from: Int
    val to: Int get() = from + 1

    /** Upgrade the manifest (catalog.json) tree by exactly one version. */
    fun migrateManifest(node: ObjectNode, ctx: MigrationContext): ObjectNode = node

    /** Upgrade one resource-detail tree by exactly one version.
     *  `type` is the resource type ("template", "theme", …). */
    fun migrateResourceDetail(type: String, node: ObjectNode, ctx: MigrationContext): ObjectNode = node
}
```

A step belongs to the **one** catalog chain and overrides only what its version
changed — `migrateManifest` to reshape the `catalog.json` tree, and/or
`migrateResourceDetail` to reshape a resource-detail tree (both default to
identity). Migrations are **pure**: no DB, no IO, no clock, no randomness —
`JsonNode` in, `JsonNode` out. `MigrationContext` carries the source and target
catalog version numbers (useful for logging or version-conditional logic).

A change that moves data between files (e.g. lift a field out of every detail
into the manifest) is one step whose `migrateManifest` and `migrateResourceDetail`
cooperate at the same version bump.

### The migrator

`CatalogSchemaMigrator` (a `@Component` in `epistola-core`) collects all
`CatalogSchemaMigration` beans into **one chain**. At startup it asserts the
chain is **contiguous and total**: exactly one step per version from `BASELINE`
to `CURRENT - 1`, no gaps, no duplicates, terminating at `CURRENT`. A malformed
chain fails application start — the same fail-fast posture as a broken Flyway
sequence.

It exposes two entry points matching the two chokepoints; each gates the
payload's `schemaVersion` against the single catalog window, runs the chain, and
binds the current-shape tree straight to the typed protocol model:

```kotlin
/** Parse, gate/upgrade the manifest to CURRENT, and bind. */
fun migrateAndBindManifest(rawManifest: ByteArray): CatalogManifest

/** Parse, gate/upgrade resource `type`'s detail to CURRENT, and bind. The
 *  detail's own `resource.type` must match the manifest-declared `type`. */
fun migrateAndBindResourceDetail(type: String, rawDetail: ByteArray): ResourceDetail
```

The orchestration at each call site becomes: **migrate → bind → import**,
replacing today's **bind → import**. Binding (`treeToValue` to
`CatalogManifest` / `ResourceDetail`) happens inside the migrator, so the
existing typed model and every downstream importer (`ImportTemplates`,
`ImportStencil`, …) are unchanged. (A lower-level pure
`migrate(tree, chain, baseline, current, apply)` backs both entry points and is
unit-tested directly.)

### Version-range enforcement

The gate is evaluated against the single catalog `CURRENT`/`BASELINE`, read from
the payload's `schemaVersion` — the manifest's, and (identically) each detail's:

| Source version                           | Behaviour                                                                                                                                                                                                                                                       |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `== CURRENT`                             | No migration; bind directly. (Fast path — today's behaviour, since the chain is empty: `BASELINE == CURRENT`.)                                                                                                                                                  |
| `v < CURRENT`, chain **empty**           | **Pass through unchanged** and bind — Phase-0 transitional. With no chain there is nothing to upgrade through, and pre-versioning stamps are unreliable, so a sub-current payload is assumed current-shape. Replaced by the two rows below once a chain exists. |
| `BASELINE ≤ v < CURRENT` (chain present) | Run the chain `v → … → CURRENT`, then bind.                                                                                                                                                                                                                     |
| `v < BASELINE` (chain present)           | **Reject** — `CatalogSchemaTooOldException` ("export predates the oldest supported wire version; re-export from a current source").                                                                                                                             |
| `v > CURRENT`                            | **Reject** — `CatalogSchemaTooNewException` ("produced by a newer Epistola; upgrade this instance").                                                                                                                                                            |
| not valid JSON, or missing / non-integer | **Reject** — `CatalogSchemaUnknownException`. (Unparseable payloads and pre-versioning artifacts are not in scope.)                                                                                                                                             |

The manifest is checked (and migrated) **first**, before any resource is
fetched, bound, or mutated — consistent with the existing "read-only pre-scan,
then mutate" discipline (ADR 0003's conflict pre-scan). Each detail is then
gated against the same catalog version as it arrives.

### Fingerprint preservation

Migrations transform _content shape_ but **must not touch `release.fingerprint`
or `release.version`**. The fingerprint is the source's identity, computed by
the source at its own wire version, and excludes `schemaVersion` by
construction. The importer's change-detection (idempotent SKIP on re-import,
`installed_fingerprint` advancement) keeps comparing the manifest's carried
fingerprint verbatim — migration is transparent to it. `schemaVersion` itself
is bumped to `CURRENT` by the final step (so a migrated-then-re-fingerprinted
payload would be self-consistent), but the carried `release.fingerprint` is
never recomputed during migration.

### Why this is EF-shaped

- **Ordered, contiguous chain.** Like EF's timestamp-ordered migrations, each
  step is exactly one version and the chain is validated total at startup.
- **Applied stepwise to reach current.** EF replays migrations from the DB's
  recorded version to `head`; we replay JSON transforms from the payload's
  `schemaVersion` to `CURRENT`.
- **The artifact records its own version.** EF reads `__EFMigrationsHistory`;
  we read the manifest's `schemaVersion`.
- **One direction.** We implement up-migrations only. Down-migration (export
  _to_ an older consumer's version) is explicitly out of scope — see
  follow-ups.

## Consequences

### Positive

- A catalog exported at any supported past wire version imports cleanly into
  the current instance with no producer involvement — across ZIP and remote
  alike.
- Non-additive wire changes stop being multi-instance coordination events;
  the producer bumps the version and ships one migration in the same PR.
- **One catalog version keeps the model simple:** a bump moves the whole catalog
  forward together — one number, one chain, one gate. A consumer upgrades the
  catalog from the version it has to current; there is no per-part bookkeeping to
  reconcile, and the manifest is the single authoritative version.
- The current typed model stays clean — historical shapes live in the
  migration chain, not in permissive production DTOs.
- The chain-integrity startup check and CI round-trip make "forgot to ship a
  migration" a build failure, not a field incident.

### Negative

- Every non-additive bump to any `epistola-model` shape carries an obligation:
  bump `CATALOG_SCHEMA_VERSION`, ship the paired `CatalogSchemaMigration`, and
  capture a golden fixture at the old version.
- A single catalog version is coarse: a one-field change to one resource bumps
  the **whole catalog** to a new version (and re-stamps every detail). We accept
  that granularity for the simplicity of one version line, one chain, and one
  gate; the migration itself still only reshapes the affected tree.
- JSON-tree transforms are not statically type-checked; correctness rests on
  the golden-fixture and round-trip tests.
- We keep historical wire knowledge (migrations + fixtures) back to `BASELINE`.
  Dropping below baseline is a deliberate, documented break.

### Neutral / follow-ups

- **Down-migration / export-to-older** is out of scope. If a future workflow
  needs a current instance to publish a catalog an _older_ consumer can read,
  that is a separate ADR (down-migrations, or a negotiated wire version on
  fetch).
- **Pre-release stance.** CLAUDE.md still says breaking changes need no
  compatibility path _while pre-release_. This ADR builds the mechanism now so
  it exists before production; until then `BASELINE == CURRENT` is permitted
  (the chain is empty and every old payload is simply rejected as before).
  The first real migration lands the first time we choose not to break.
- **Binary assets** are referenced by `contentUrl`, not embedded in JSON, so
  they are outside the JSON-migration scope; a migration that changes how
  assets are referenced rewrites the _reference_ in the detail tree, and the
  binary fetch is unaffected.
- The snapshot format (`RestoreTenantSnapshot`, `SNAPSHOT_SCHEMA_VERSION`)
  already does a `require(version <= SUPPORTED)` gate but has no migration
  chain. It is a candidate to adopt the same `CatalogSchemaMigrator` shape
  later; this ADR scopes only the catalog exchange wire format.

## Implementation references

- Current version constant: `CatalogConstants.CATALOG_SCHEMA_VERSION`.
- Manifest binding chokepoints: `ImportCatalogZip.kt` (manifest read),
  `CatalogClient.fetchManifest()`.
- Resource-detail binding chokepoints: `ImportCatalogZip.kt` (stencil
  pre-scan + per-resource loop), `CatalogClient.fetchResourceDetail()`.
- Remote-subscribe orchestration: `InstallFromCatalog.kt`.
- Export stamping (where `schemaVersion` is written): `CatalogContentBuilder`.
- Fingerprint exclusion of `schemaVersion`: `CatalogCanonicalizer`,
  documented in [`catalog-versioning.md`](../catalog-versioning.md).
- New: `CatalogSchemaMigrator`, `CatalogSchemaMigration`, `MigrationContext`,
  the `CatalogSchema*Exception` family, and the `migrations/` package under
  `app.epistola.suite.catalog`. Implementation plan:
  [`plans/catalog-wire-format-migrations.md`](../plans/catalog-wire-format-migrations.md).
