# Catalog protocol stability & compatibility

> Part of [catalog import/export](README.md). This page states the **stability
> contract** for the catalog exchange wire format — what a consumer of a
> catalog (a ZIP, a subscribed remote URL, a bundled catalog) can rely on
> across Epistola versions. The per-field shape of each part lives under
> [`v4/`](v4/); this page is about the _guarantees_ around that shape, not the
> fields themselves.

## The frozen version

At **1.0**, the catalog exchange wire format is **`schemaVersion 4`** — a single,
catalog-wide version that the whole bundle moves together (the manifest is
authoritative for it and every resource detail carries the same number; see
[ADR 0007](../adr/0007-catalog-wire-format-migrations.md)). The exact shape of
each part at this version is documented in [`v4/`](v4/).

From 1.0 onward the wire format is **versioned and append-only**:

- **`v4` is never changed in place.** Once shipped, the meaning and presence of
  every required field at a given `schemaVersion` is fixed. We do not
  retroactively rename, retype, move, or make-required a field within an
  existing version.
- **Breaking changes bump the version.** Any change that an existing reader
  could not round-trip (see the table below) increments
  `CATALOG_SCHEMA_VERSION` to `5`, `6`, … and ships a **migration step** that
  upgrades an older payload to the new shape on import. There is no separate
  "compatibility shim" to write per change — the [migration
  chain](../adr/0007-catalog-wire-format-migrations.md) _is_ the compatibility
  mechanism.

## What is compatible vs. breaking

| Change to the wire format                                                  | Compatible? | What happens                                                            |
| -------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------------------- |
| Add a new **optional** field                                               | ✅ additive | Same `schemaVersion`. Old readers ignore it; new readers default it.    |
| Add a new resource **type** that older readers may ignore                  | ✅ additive | Same `schemaVersion`. (Install order and dependency rules still apply.) |
| Rename / retype / move a field, or make an existing field **required**     | ❌ breaking | Bump `schemaVersion`; ship a migration step `vN → vN+1`.                |
| Change a polymorphic discriminator (`resource.type`) or its allowed values | ❌ breaking | Bump `schemaVersion`; ship a migration step.                            |
| Remove a field that readers depend on                                      | ❌ breaking | Bump `schemaVersion`; ship a migration step.                            |

Additive changes rely on Jackson's ignore-unknown behaviour in **both**
directions: an old export simply lacks a new optional field, and a new export's
extra optional field is ignored by an old reader. That is why optional-field
growth stays within one `schemaVersion`.

## What a consumer can rely on

- **A `v4` payload binds on any 1.0+ instance** without the producer's
  involvement. A payload at an **older** supported version is migrated forward
  on import; a payload **newer** than the instance understands is rejected with
  an actionable error (upgrade this instance), and one **older** than the oldest
  kept migration is rejected (re-export from a current source). The full
  decision table is the [wire-format version gate](README.md#wire-format-version-gate).
- **One bundle, one version.** Every file in a catalog (manifest + every
  resource detail) carries the same `schemaVersion`; a detail whose stamp
  differs from the manifest's is rejected as malformed.
- **`release.fingerprint` is independent of the wire version.** The fingerprint
  is the source's content identity and **excludes** `schemaVersion` by
  construction, so migrating a payload's shape never changes which release it
  represents — change detection (idempotent re-import, upgrade prompts) is
  unaffected. See [`catalog-versioning.md`](../catalog-versioning.md#fingerprint-algorithm).

## What is _not_ covered

- **Down-migration** (exporting _to_ an older consumer's version) is out of
  scope; the chain is forward-only (see ADR 0007 follow-ups).
- **Binary asset content** is referenced by `contentUrl`, not embedded in the
  JSON, so it sits outside the wire-shape contract; only the _reference_ in the
  detail tree is versioned.
- The **release version** (SemVer content version) and **content fingerprint**
  are separate axes from the wire `schemaVersion` — see the [three version
  axes](README.md#parts--contract-versions).

## Changing the protocol after 1.0

When a non-round-trip-compatible change is genuinely needed:

1. Copy [`v4/`](v4/) to `v5/` and edit the changed part(s) there, leaving `v4/`
   in place so the whole wire format diffs against its predecessor as a set.
2. Bump `CATALOG_SCHEMA_VERSION` and add one `CatalogSchemaMigration` step to
   the single chain (`v4 → v5`). The startup chain-integrity check and the
   golden-fixture round-trip tests enforce that the step exists and works.
3. Record the change here and in the part's own `## Shape history`.

See [ADR 0007](../adr/0007-catalog-wire-format-migrations.md) for the full
rationale and [`plans/catalog-wire-format-migrations.md`](../plans/catalog-wire-format-migrations.md)
for the migration roadmap.
