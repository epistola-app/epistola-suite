<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0003: Stencil Version in Catalog Export

- **Status:** Accepted
- **Date:** 2026-05-20
- **Deciders:** Epistola team
- **Tags:** catalog, stencils, exchange, versioning

## Context

A `Stencil` in Epistola has a sequence of immutable published versions stored
in `stencil_versions` keyed by `(tenant_key, catalog_key, stencil_key, id)`. A
template references a specific stencil version through a stencil node whose
`props.version: Int` pins the consumer to that exact published version. The
renderer looks the version up at generation time: if it does not exist, the
template fails to render.

Before this ADR, the catalog wire format (`StencilResource`, `epistola-model
0.5.x`) carried only the stencil's _latest published content_ — no version
number. On import, `ImportStencil` always inserted that content as
`MAX(target.stencil_versions.id) + 1`. Two consequences fell out of this
shape:

1. **Round-trip is lossy.** Source-side stencil `S` with versions `v1, v2, v3`
   exports only `v3`'s content. Templates pinning `v2` keep their
   `props.version: 2` intact, but on import the target stencil has only
   `v1` (the newly-assigned first version) holding what was source's `v3`
   content. The pin to `v2` no longer resolves.
2. **The pre-existing export precheck under-detects drift.** The check
   (`FindStencilVersionExportConflicts`) only fired when _different_
   templates pinned _different_ versions of the same stencil. A catalog where
   every template consistently pinned `v2` but the latest published was `v3`
   passed the check yet still produced the round-trip failure above.

We need stencil-version-pin behaviour to survive a catalog round-trip
deterministically, with explicit conflict handling when a re-import collides
with already-installed content.

### Decision drivers

- **Round-trip determinism.** A template that renders correctly in source must
  render correctly in target after import. The pin number is meaningful
  user-visible data, not an implementation detail.
- **Operator visibility.** Conflicts must be discoverable before they break
  rendering, with structured messages naming the affected stencil and
  version.
- **No silent semantic drift.** A re-import must never replace a published
  stencil version's content with different content without the operator
  saying so.
- **Mirror-mode integrity.** SUBSCRIBED catalogs and AUTHORED REPLACE imports
  are defined as "source wins" mirrors. Any conflict-resolution mode that
  diverges target from source contradicts that contract.
- **Pre-release pragmatism.** Epistola is not yet in production; the team
  prefers a clean breaking wire-format change over a long deprecation path.

## Considered options

### Option A — Block exports until pin discipline is enforced

Keep the wire format as-is. Strengthen the export precheck so every template
in the catalog must pin the _latest_ published version of every stencil it
uses. Exports that pass the check are guaranteed safe under the current
import behaviour (assign `MAX+1`, content matches the only pinned version).

#### A — Pros

- No wire-format change. No multi-repo coordination.
- Existing ZIPs remain valid.

#### A — Cons

- Forces operators to upgrade every stale template usage before they can
  export. For a large catalog with diverged template branches this is real
  manual work.
- Loses historical pin information at the wire format. A catalog that _wants_
  to ship `v1` and `v2` of the same stencil cannot.
- Does not solve the underlying mismatch — it just hides it behind a
  discipline gate.

### Option B — Import-side rewrite of template stencil pins

Keep the wire format unchanged (latest content only). Have `ImportStencil`
record the version it assigned in target. `ImportTemplates` rewrites every
stencil-node `props.version` in the imported template models to that
assigned version.

#### B — Pros

- Round-trip "works" with no wire-format change.
- Simple at the import boundary — no merge collisions because version
  numbers in target are just an import-order sequence.

#### B — Cons

- Still vulnerable to **silent semantic drift**: a source where templates
  consistently pin `v2` but the latest published is `v3` exports `v3`
  content. After rewrite the templates resolve `v3`'s content, not `v2`'s
  — the pin's meaning changes invisibly.
- Cannot honour a source that uses _multiple_ versions of the same stencil
  in different templates. Both pins would have to map to the single
  imported version.
- Historical versions in source are lost in target.
- Requires its own stricter export check to avoid the silent-drift case,
  recreating most of the operator-visibility benefit anyway.

### Option C — Carry the version on the wire and preserve it on import (chosen)

Add a required `version: Int` to `StencilResource`. The exporter ships the
latest published version _plus_ its number. On import:

- The target installs the stencil at the _given_ version number.
- A re-import that finds the same `(slug, version)` with byte-identical
  JSONB content is an idempotent no-op (`InstallStatus.SKIPPED`).
- A re-import that finds the same `(slug, version)` with different content
  is a **conflict** — resolution policy is chosen by the operator (see
  below).

#### C — Pros

- Templates keep their original pin semantics across a round-trip.
- Re-imports are detectably idempotent (same fingerprint → no churn).
- Historical pins can be preserved (different stencils ship at their own
  installed version numbers).
- Conflicts surface explicitly instead of being papered over.

#### C — Cons

- Breaking wire-format change. Requires bumping `epistola-model` and a
  multi-repo PR.
- Adds merge-collision design surface (handled by the conflict-resolution
  policy, below).

### Option D — Renumber + carry version

Hybrid. Carry the version on the wire (C), but on conflict always renumber
to `MAX+1` and rewrite the imported templates' pins (B). Never fail.

#### D — Pros

- Always succeeds; least operator friction.

#### D — Cons

- A conflict is a meaningful signal that source and target diverged. Silently
  resolving it means the operator never knows the catalog drifted from
  source — the very condition this ADR aims to make visible.
- For a SUBSCRIBED catalog (managed mirror), silent renumber breaks the
  mirror invariant.

## Decision

We adopt **Option C** with explicit per-mode conflict policy.

### Wire format

`epistola-model 0.5.3 → 0.6.0`:

```kotlin
data class StencilResource(
    override val slug: String,
    override val name: String,
    val version: Int,                       // ← required, no default
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
) : CatalogResource
```

`version` is the published version number of the stencil whose `content` is
carried in this resource. It has **no default value**: a ZIP produced by a
pre-`0.6.0` exporter lacks the field, Jackson rejects the deserialisation,
and the import fails with `"missing version, please re-export"`. The project
is pre-release, so no compatibility shim is provided.

### Stricter export precheck

`FindStencilVersionExportConflicts` now flags any own-catalog stencil whose
published templates pin a version that is **not the latest published
version** of that stencil — irrespective of whether the pins agree with each
other. The folded SQL predicate is `bool_or(stencil_version <>
latest_published_version)` grouped per stencil. The check fires for two
failure modes that previously needed thinking about separately:

- **Inconsistent**: different templates pin different versions.
- **Stale**: every template pins the same version, but that version is not
  the latest published.

Both surface in the same `MultipleStencilVersionsInUseException`. The dialog
and badge text are now `pinned vX (latest vY)`.

### Conflict resolution policy

`ImportCatalogZip` gains a parameter
`onStencilConflict: OnStencilConflict = FAIL` with two values:

- `FAIL` (default): on the first read-only pre-scan, collect every stencil
  whose `(slug, version)` already exists in target with **different** content
  (JSONB semantic equality, not text). If the list is non-empty, abort the
  _entire_ import before any mutation and throw
  `StencilVersionImportConflictsException` carrying the full list. The
  catalog is unchanged. The operator sees every conflict at once and can
  retry with `RENUMBER` if appropriate.
- `RENUMBER`: install the conflicting source version at `MAX(target.version)
  - 1`instead of the requested number.`ImportStencil`returns the assigned
number;`ImportCatalogZip`collects a`Map<StencilKey, StencilRenumber>`during the install loop and passes it to`ImportTemplates`.
`ImportTemplates`walks every imported template model's`nodes`and
rewrites`props.version`for any stencil node whose`stencilId`is in the
map AND whose source pin equals`sourceVersion`. Pre-existing templates in
    the target are not touched — they continue to resolve their own
    pre-renumber version.

`RENUMBER` is **rejected upfront** for any mode other than `AUTHORED MERGE`.
Both SUBSCRIBED and AUTHORED REPLACE are mirror-semantic imports — letting
renumber diverge target from source contradicts the import contract. The
orchestrator throws `IllegalArgumentException` before any read.

Cross-catalog stencil refs (`props.catalogKey != ownCatalog`) are never
rewritten; they target a different catalog's stencil and that catalog's
versioning is independent.

### Why not B

B's appeal was simplicity — no wire-format change, no merge questions. The
deal-breaker is **silent semantic drift**: B requires the stricter export
check anyway (without it, a stale-pin source corrupts after import), and
once the check is in place B's only remaining benefit is the absent
wire-format change. We judged the cost of a one-time pre-release
breaking-change wire bump to be smaller than the cost of a permanently
lossy round-trip.

### Why not D

D resolves every conflict transparently and is the lowest-friction option.
We rejected it because for a SUBSCRIBED catalog it silently breaks the
mirror invariant, and for AUTHORED MERGE it suppresses a signal we want
the operator to see ("source and target diverged"). The `RENUMBER` path is
available — but as an explicit opt-in, not a default.

## Consequences

### Positive

- Templates' stencil-version pins survive a round-trip deterministically.
- Re-imports of an unchanged source are an idempotent no-op (skips, not
  upserts).
- Conflicts are visible to the operator in one structured report instead of
  per-resource failures or post-hoc rendering breakage.
- The catalog-browse view marks stencils whose pins are stale relative to
  the latest published version, so the issue is fixable before export-time.
- The implementation collapses two prior concerns (multi-version vs
  stale-single-version usage) into one stricter precheck.

### Negative

- `epistola-model 0.6.0` is a breaking change. Every consumer producing or
  consuming `StencilResource` must add the field. Existing catalog ZIPs need
  to be re-exported.
- Operators must consciously decide between `FAIL` and `RENUMBER` when they
  re-import a diverged catalog. There is no single "just make it work"
  default in conflict cases — by design.
- The `RENUMBER` UX in this iteration requires the operator to re-upload the
  ZIP with the "Allow renumber" checkbox checked, because we did not add a
  server-side stash of pending imports. A future iteration could stash the
  bytes (e.g. Caffeine cache with TTL) for a single-click retry.

### Neutral / follow-ups

- The wire format does not currently carry _all_ published stencil versions
  — only the latest one. A catalog with multiple actively-pinned versions
  would still need the stricter precheck to pass before export. A future
  extension could allow shipping multiple versions per stencil; this ADR
  leaves the question open because no current workflow requires it.
- The current implementation is integration-tested in
  `StencilVersionImportConflictTest` (six scenarios) and
  `ExportBlocksMultipleStencilVersionsTest` (multi-version + stale-pin).
  Future regressions on the conflict semantics are expected to extend
  those.

## Implementation references

- Wire format: `StencilResource.version` in `epistola-contract` / `epistola-model 0.6.0`.
- Export: `ExportStencils` SQL selects `sv.id` for the latest published version per stencil.
- Export precheck:
  `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/queries/FindStencilVersionExportConflicts.kt`.
- Import command + conflict modes:
  `ImportStencil`, `OnStencilConflict`, `StencilVersionConflictException`,
  `StencilVersionImportConflictsException` under
  `modules/epistola-core/src/main/kotlin/app/epistola/suite/catalog/commands/`.
- Orchestration + template pin rewrite:
  `ImportCatalogZip` (pre-scan + renumber-map accumulator), `ImportTemplates`
  (`stencilRenumbers` parameter + `rewriteStencilPins`).
- UI: catalog-browse stencil badge, export-blocked dialog (`alert-error`),
  import-dialog `onStencilConflict` checkbox, inline conflict-report
  fragment (`import-conflict-content`).
- Operator-facing documentation: [`exchange/README.md`](../exchange/README.md).
